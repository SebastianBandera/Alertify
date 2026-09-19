import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { filter, map, Observable, Subject } from 'rxjs';

import { DashboardAlertCard } from '../../features/dashboard/dashboard-card';
import { SystemStatusSummary } from '../api/system-status-api.service';
import { AuthService } from '../auth/auth.service';
import { RUNTIME_CONFIG } from '../config/runtime-config';

const INITIAL_RECONNECT_DELAY_MILLIS = 1_000;
const MAX_RECONNECT_DELAY_MILLIS = 30_000;
const TOKEN_RENEWAL_LEEWAY_MILLIS = 15_000;
const REQUEST_TIMEOUT_MILLIS = 15_000;

export type AdminEventConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting';

interface AdminEventPayloads {
  readonly SYSTEM_STATUS: SystemStatusSummary;
  readonly DASHBOARD_ALERT: DashboardAlertCard;
  readonly DASHBOARD_ALERT_REMOVED: { readonly alertId: number };
}

export type AdminEventName = keyof AdminEventPayloads;

const EVENT_NAMES: ReadonlySet<string> = new Set<AdminEventName>(['SYSTEM_STATUS', 'DASHBOARD_ALERT', 'DASHBOARD_ALERT_REMOVED']);

function isEventName(value: unknown): value is AdminEventName {
  return typeof value === 'string' && EVENT_NAMES.has(value);
}

type AdminEventMessage = {
  [Name in AdminEventName]: {
    readonly name: Name;
    readonly payload: AdminEventPayloads[Name];
  }
}[AdminEventName];

interface PendingRequest {
  readonly resolve: (payload: unknown) => void;
  readonly reject: (reason: Error) => void;
  readonly timeout: number;
}

function tokenExpiresAt(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;

    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(payload.length / 4) * 4, '=');
    const decoded = JSON.parse(atob(normalized)) as { exp?: unknown };
    return typeof decoded.exp === 'number' ? decoded.exp * 1_000 : null;
  } catch {
    return null;
  }
}

@Injectable({ providedIn: 'root' })
export class AdminEventChannelService {
  private readonly authService = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;
  private readonly events = new Subject<AdminEventMessage>();
  private readonly pendingRequests = new Map<string, PendingRequest>();
  readonly connectionState = signal<AdminEventConnectionState>('idle');
  private socket: WebSocket | null = null;
  private reconnectTimer: number | null = null;
  private tokenRenewalTimer: number | null = null;
  private reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS;
  private stopped = true;

  constructor() {
    this.destroyRef.onDestroy(() => this.stop());
  }

  start(): void {
    if (!this.stopped) return;

    this.stopped = false;
    this.connectionState.set('connecting');
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== null) {
      this.document.defaultView?.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearTokenRenewalTimer();
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.onclose = null;
      socket.close();
    }
    this.rejectPendingRequests('The administrative event channel was closed.');
    this.connectionState.set('idle');
  }

  on<Name extends AdminEventName>(name: Name): Observable<AdminEventPayloads[Name]> {
    return this.events.pipe(
      filter((message) => message.name === name),
      /* The discriminated union narrows per member, not per generic name, so the payload is asserted once here. */
      map((message) => message.payload as AdminEventPayloads[Name]),
    );
  }

  request<Response>(name: string, payload: unknown): Promise<Response> {
    const socket = this.socket;
    const window = this.document.defaultView;
    if (!window || !socket || socket.readyState !== WebSocket.OPEN || this.connectionState() !== 'connected') {
      return Promise.reject(new Error('The administrative event channel is not connected.'));
    }

    const requestId = window.crypto.randomUUID();
    return new Promise<Response>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pendingRequests.delete(requestId);
        reject(new Error('The administrative request timed out.'));
      }, REQUEST_TIMEOUT_MILLIS);
      this.pendingRequests.set(requestId, {
        resolve: (response) => resolve(response as Response),
        reject,
        timeout,
      });
      try {
        socket.send(JSON.stringify({ type: 'REQUEST', requestId, name, payload }));
      } catch (error) {
        window.clearTimeout(timeout);
        this.pendingRequests.delete(requestId);
        reject(error instanceof Error ? error : new Error('The administrative request could not be sent.'));
      }
    });
  }

  private connect(): void {
    const window = this.document.defaultView;
    if (this.stopped || !window || this.socket || this.reconnectTimer !== null) return;

    const socketUrl = new URL(`${this.apiBaseUrl}/api/admin/events`);
    socketUrl.protocol = socketUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new window.WebSocket(socketUrl);
    this.socket = socket;
    socket.onopen = () => void this.authenticate(socket);
    socket.onmessage = (event) => this.handleMessage(event);
    socket.onclose = () => this.handleSocketClosed(socket);
    socket.onerror = () => socket.close();
  }

  private async authenticate(socket: WebSocket): Promise<void> {
    try {
      const token = await this.authService.getAccessToken();
      if (socket !== this.socket || socket.readyState !== WebSocket.OPEN) return;

      socket.send(JSON.stringify({ type: 'AUTH', token }));
      this.scheduleTokenRenewal(token);
    } catch {
      socket.close();
    }
  }

  private handleMessage(event: MessageEvent<string>): void {
    try {
      const message = JSON.parse(event.data) as Record<string, unknown>;
      switch (message['type']) {
        case 'AUTHENTICATED':
          this.connectionState.set('connected');
          this.reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS;
          break;
        case 'EVENT':
          this.handleEvent(message);
          break;
        case 'RESPONSE':
          this.completeRequest(message, false);
          break;
        case 'ERROR':
          this.completeRequest(message, true);
          break;
      }
    } catch {
      // A malformed optional message is ignored; transport recovery remains independent.
    }
  }

  private handleEvent(message: Record<string, unknown>): void {
    const name = message['name'];
    if (!isEventName(name) || !message['payload']) return;

    this.events.next({ name, payload: message['payload'] } as AdminEventMessage);
  }

  private completeRequest(message: Record<string, unknown>, failed: boolean): void {
    const requestId = message['requestId'];
    if (typeof requestId !== 'string') return;

    const pending = this.pendingRequests.get(requestId);
    if (!pending) return;

    this.pendingRequests.delete(requestId);
    this.document.defaultView?.clearTimeout(pending.timeout);
    if (failed) {
      const code = typeof message['code'] === 'string' ? message['code'] : 'REQUEST_FAILED';
      pending.reject(new Error(code));
      return;
    }
    pending.resolve(message['payload']);
  }

  private scheduleTokenRenewal(token: string): void {
    this.clearTokenRenewalTimer();
    const expiresAt = tokenExpiresAt(token);
    if (!expiresAt) {
      this.socket?.close();
      return;
    }

    const delay = Math.max(1_000, expiresAt - Date.now() - TOKEN_RENEWAL_LEEWAY_MILLIS);
    this.tokenRenewalTimer = this.document.defaultView?.setTimeout(() => {
      const socket = this.socket;
      if (socket) void this.authenticate(socket);
    }, delay) ?? null;
  }

  private handleSocketClosed(socket: WebSocket): void {
    if (socket !== this.socket) return;

    this.socket = null;
    this.clearTokenRenewalTimer();
    this.rejectPendingRequests('The administrative event channel was disconnected.');
    if (this.stopped) return;

    this.connectionState.set('reconnecting');
    const baseDelay = this.reconnectDelayMillis;
    const delay = Math.round(baseDelay * (0.8 + Math.random() * 0.4));
    this.reconnectDelayMillis = Math.min(MAX_RECONNECT_DELAY_MILLIS, baseDelay * 2);
    this.reconnectTimer = this.document.defaultView?.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay) ?? null;
  }

  private rejectPendingRequests(message: string): void {
    for (const request of this.pendingRequests.values()) {
      this.document.defaultView?.clearTimeout(request.timeout);
      request.reject(new Error(message));
    }
    this.pendingRequests.clear();
  }

  private clearTokenRenewalTimer(): void {
    if (this.tokenRenewalTimer === null) return;

    this.document.defaultView?.clearTimeout(this.tokenRenewalTimer);
    this.tokenRenewalTimer = null;
  }
}
