import { DOCUMENT } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { LocalizationService } from '../../core/i18n/localization.service';
import { SystemStatusSummary } from '../../core/api/system-status-api.service';

const INITIAL_RECONNECT_DELAY_MILLIS = 1_000;
const MAX_RECONNECT_DELAY_MILLIS = 30_000;
const TOKEN_RENEWAL_LEEWAY_MILLIS = 15_000;

interface StatusMessage {
  readonly type: 'STATUS';
  readonly summary: SystemStatusSummary;
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

@Component({
  selector: 'app-status-ticker',
  templateUrl: './status-ticker.component.html',
  styleUrl: './status-ticker.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusTickerComponent implements AfterViewInit {
  protected readonly localization = inject(LocalizationService);
  private readonly authService = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly apiBaseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;
  private readonly ticker = viewChild<ElementRef<HTMLElement>>('ticker');
  protected readonly summary = signal<SystemStatusSummary | null>(null);
  protected readonly scrolling = signal(false);
  protected readonly message = computed(() => {
    const summary = this.summary();
    if (!summary) return null;

    const messages: string[] = [];
    const activeExecutions = summary.activeAlertExecutions + summary.activeProcedureExecutions;
    const waitingExecutions = summary.waitingAlertExecutions + summary.waitingProcedureExecutions;
    if (summary.maintenanceModeEnabled) {
      messages.push(activeExecutions === 0
        ? this.localization.translate('statusTicker.maintenanceReady')
        : this.localization.translate('statusTicker.maintenanceActive').replace('{count}', activeExecutions.toString()));
    }
    if (waitingExecutions > 0) {
      messages.push(this.localization.translate('statusTicker.waitingTasks').replace('{count}', waitingExecutions.toString()));
    }
    for (const worker of summary.saturatedWorkers) {
      messages.push(this.localization.translate('statusTicker.saturatedWorker')
        .replace('{worker}', worker.workerName)
        .replace('{count}', worker.waitingCount.toString()));
    }
    if (summary.cronQuietHoursActive) messages.push(this.localization.translate('statusTicker.cronQuietHours'));
    return messages.length > 0 ? messages.join(' · ') : null;
  });
  private socket: WebSocket | null = null;
  private reconnectTimer: number | null = null;
  private tokenRenewalTimer: number | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS;
  private destroyed = false;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.stop();
    });
  }

  ngAfterViewInit(): void {
    this.document.addEventListener('visibilitychange', this.handleVisibilityChange);
    this.resizeObserver = new ResizeObserver(() => this.updateScrolling());
    const ticker = this.ticker()?.nativeElement;
    if (ticker) this.resizeObserver.observe(ticker);
    this.updateConnection();
  }

  private readonly handleVisibilityChange = (): void => this.updateConnection();

  private updateConnection(): void {
    if (this.document.visibilityState === 'visible') {
      this.connect();
      return;
    }

    this.disconnect();
  }

  private connect(): void {
    if (this.destroyed || this.socket || this.reconnectTimer !== null) return;

    const socketUrl = new URL(`${this.apiBaseUrl}/api/system-status/ticker`);
    socketUrl.protocol = socketUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(socketUrl);
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
      const message = JSON.parse(event.data) as Partial<StatusMessage>;
      if (message.type !== 'STATUS' || !message.summary) return;

      this.summary.set(message.summary);
      this.reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS;
      queueMicrotask(() => this.updateScrolling());
    } catch {
      // Malformed optional status data is ignored; the socket can still recover on its next update.
    }
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
    this.summary.set(null);
    this.scrolling.set(false);
    if (this.destroyed || this.document.visibilityState !== 'visible') return;

    const delay = this.reconnectDelayMillis;
    this.reconnectDelayMillis = Math.min(MAX_RECONNECT_DELAY_MILLIS, delay * 2);
    this.reconnectTimer = this.document.defaultView?.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay) ?? null;
  }

  private updateScrolling(): void {
    const ticker = this.ticker()?.nativeElement;
    if (!ticker || !this.message() || this.document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      this.scrolling.set(false);
      return;
    }

    this.resizeObserver?.observe(ticker);

    const track = ticker.firstElementChild as HTMLElement | null;
    this.scrolling.set(Boolean(track && track.scrollWidth > ticker.clientWidth));
  }

  private disconnect(): void {
    if (this.reconnectTimer !== null) {
      this.document.defaultView?.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearTokenRenewalTimer();
    this.socket?.close();
    this.socket = null;
    this.summary.set(null);
    this.scrolling.set(false);
  }

  private stop(): void {
    this.disconnect();
    this.resizeObserver?.disconnect();
    this.document.removeEventListener('visibilitychange', this.handleVisibilityChange);
  }

  private clearTokenRenewalTimer(): void {
    if (this.tokenRenewalTimer === null) return;

    this.document.defaultView?.clearTimeout(this.tokenRenewalTimer);
    this.tokenRenewalTimer = null;
  }
}
