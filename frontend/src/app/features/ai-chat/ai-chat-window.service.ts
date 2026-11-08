import { Injectable, inject, signal } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { AiChatSessionService } from './ai-chat-session.service';

type MessageKind = 'READY' | 'INIT' | 'ACK' | 'UPDATE' | 'RETURN' | 'RETURNED' | 'CLOSE';
interface ChatWindowMessage {
  readonly type: 'ALERTIFY_AI_CHAT_STATE';
  readonly kind: MessageKind;
  readonly pairId: string;
  readonly user: string;
  readonly session: string;
  readonly sequence: number;
  readonly snapshot?: unknown;
}
interface WindowCallbacks {
  capture(): void;
  restore(): void;
  detached(): void;
}

/** A private, acknowledged handoff between one panel and its own popup. No browser storage. */
@Injectable({ providedIn: 'root' })
export class AiChatWindowService {
  private readonly auth = inject(AuthService);
  private readonly state = inject(AiChatSessionService);
  readonly detached = signal(false);
  readonly transferring = signal(false);
  readonly error = signal('');
  private callbacks: WindowCallbacks | null = null;
  private popout = false;
  private peer: Window | null = null;
  private pairId = '';
  private connected = false;
  private sequence = 0;
  private receivedSequence = -1;
  private timeout: number | null = null;
  private polling: number | null = null;
  private readonly listener = (event: MessageEvent<unknown>) => this.receive(event);
  private readonly closing = () => {
    if (!this.popout || !this.connected) return;
    this.callbacks?.capture();
    this.post('CLOSE', true);
  };

  start(popout: boolean, callbacks: WindowCallbacks): boolean {
    this.popout = popout;
    this.callbacks = callbacks;
    window.addEventListener('message', this.listener);
    window.addEventListener('pagehide', this.closing);
    const currentUrl = new URL(location.href);
    const pairId = currentUrl.searchParams.get('handoff');
    if (!popout || !pairId || !window.opener) return false;
    this.peer = window.opener;
    this.pairId = pairId;
    this.transferring.set(true);
    currentUrl.searchParams.delete('handoff');
    history.replaceState(history.state, '', currentUrl.pathname + currentUrl.search + currentUrl.hash);
    this.post('READY');
    this.polling = window.setInterval(() => this.post('READY'), 250);
    this.deadline();
    return true;
  }

  detach(): void {
    if (this.transferring()) return;
    if (this.peer && !this.peer.closed) { this.peer.focus(); return; }
    this.callbacks?.capture();
    this.error.set('');
    this.pairId = crypto.randomUUID();
    const url = new URL('ai-chat', document.baseURI);
    url.searchParams.set('handoff', this.pairId);
    this.peer = window.open(url, `alertify-ai-chat-${this.pairId}`, 'popup,width=560,height=820');
    if (!this.peer) { this.fail('ai.chat.transferFailed'); return; }
    this.sequence = 0;
    this.receivedSequence = -1;
    this.transferring.set(true);
    this.deadline();
    this.polling = window.setInterval(() => {
      if (!this.peer?.closed) return;
      const wasDetached = this.detached();
      this.clearPeer();
      if (wasDetached) this.callbacks?.restore();
    }, 250);
  }

  publish(): void {
    if (this.popout && this.connected && !this.transferring()) this.post('UPDATE', true);
  }

  reattach(): void {
    if (this.transferring()) return;
    if (!this.connected || !this.peer || this.peer.closed) { this.error.set('ai.chat.mainWindowUnavailable'); return; }
    this.callbacks?.capture();
    this.error.set('');
    this.transferring.set(true);
    this.post('RETURN', true);
    this.deadline();
  }

  stop(): void {
    window.removeEventListener('message', this.listener);
    window.removeEventListener('pagehide', this.closing);
    this.clearPeer();
    this.callbacks = null;
  }

  private receive(event: MessageEvent<unknown>): void {
    if (event.origin !== location.origin || !this.peer || event.source !== this.peer || !event.data || typeof event.data !== 'object') return;
    const message = event.data as ChatWindowMessage;
    if (message.type !== 'ALERTIFY_AI_CHAT_STATE' || message.pairId !== this.pairId
      || message.user !== this.auth.userIdentifier || message.session !== (this.auth.sessionIdentifier ?? '')
      || !Number.isSafeInteger(message.sequence) || message.sequence < 0) return;
    if (!this.popout && message.kind === 'READY' && this.transferring() && !this.connected) {
      this.post('INIT', true);
    } else if (this.popout && message.kind === 'INIT' && !this.connected && this.transferring()) {
      if (!this.state.replace(message.snapshot)) return;
      this.clearTimers();
      this.connected = true;
      this.callbacks?.restore();
      this.post('ACK');
      this.transferring.set(false);
    } else if (!this.popout && message.kind === 'ACK' && this.transferring() && !this.connected) {
      if (this.timeout !== null) window.clearTimeout(this.timeout);
      this.timeout = null;
      this.connected = true;
      this.transferring.set(false);
      this.detached.set(true);
      this.callbacks?.detached();
    } else if (!this.popout && this.connected && ['UPDATE', 'RETURN', 'CLOSE'].includes(message.kind)) {
      if (message.sequence <= this.receivedSequence || !this.state.replace(message.snapshot)) return;
      this.receivedSequence = message.sequence;
      if (message.kind === 'UPDATE') return;
      this.post('RETURNED');
      this.clearPeer();
      this.callbacks?.restore();
    } else if (this.popout && message.kind === 'RETURNED' && this.connected && this.transferring()) {
      this.clearPeer();
      window.close();
    }
  }

  private post(kind: MessageKind, snapshot = false): void {
    try {
      this.peer?.postMessage({ type: 'ALERTIFY_AI_CHAT_STATE', kind, pairId: this.pairId,
        user: this.auth.userIdentifier, session: this.auth.sessionIdentifier ?? '', sequence: ++this.sequence,
        ...(snapshot ? { snapshot: this.state.snapshot() } : {}) } satisfies ChatWindowMessage, location.origin);
    } catch { this.error.set('ai.chat.transferFailed'); }
  }

  private deadline(): void {
    if (this.timeout !== null) window.clearTimeout(this.timeout);
    this.timeout = window.setTimeout(() => {
      if (!this.popout) { this.peer?.close(); this.clearPeer(); }
      else { this.clearTimers(); this.transferring.set(false); }
      this.error.set('ai.chat.transferFailed');
    }, 15000);
  }

  private fail(code: string): void {
    this.clearPeer();
    this.error.set(code);
  }

  private clearTimers(): void {
    if (this.timeout !== null) window.clearTimeout(this.timeout);
    if (this.polling !== null) window.clearInterval(this.polling);
    this.timeout = null;
    this.polling = null;
  }

  private clearPeer(): void {
    this.clearTimers();
    this.peer = null;
    this.connected = false;
    this.pairId = '';
    this.detached.set(false);
    this.transferring.set(false);
  }
}
