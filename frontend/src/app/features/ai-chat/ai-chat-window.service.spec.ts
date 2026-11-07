import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { AiChatSessionService } from './ai-chat-session.service';
import { AiChatWindowService } from './ai-chat-window.service';

describe('AiChatWindowService', () => {
  let windows: AiChatWindowService;
  let state: AiChatSessionService;
  let peer: { closed: boolean; postMessage: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn>; focus: ReturnType<typeof vi.fn> };
  let callbacks: { capture: () => void; restore: () => void; detached: () => void };
  let originalOpener: PropertyDescriptor | undefined;
  beforeEach(() => {
    vi.useFakeTimers();
    history.replaceState(null, '', '/');
    originalOpener = Object.getOwnPropertyDescriptor(window, 'opener');
    peer = { closed: false, postMessage: vi.fn(), close: vi.fn(), focus: vi.fn() };
    callbacks = { capture: vi.fn(), restore: vi.fn(), detached: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { userIdentifier: 'realm:one', sessionIdentifier: 'session-one' } }] });
    windows = TestBed.inject(AiChatWindowService);
    state = TestBed.inject(AiChatSessionService);
    state.select(1);
    state.capture(1, 'panel draft', 'PLAN', 'ASK');
    vi.spyOn(window, 'open').mockReturnValue(peer as unknown as Window);
  });
  afterEach(() => {
    windows.stop();
    if (originalOpener) Object.defineProperty(window, 'opener', originalOpener);
    else Reflect.deleteProperty(window, 'opener');
    history.replaceState(null, '', '/');
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  function deliver(kind: string, extra: Record<string, unknown> = {}, origin = location.origin, source = peer as unknown as Window): void {
    const opened = vi.mocked(window.open).mock.calls[0]?.[0];
    const pairId = opened ? new URL(String(opened)).searchParams.get('handoff') : 'child-pair';
    window.dispatchEvent(new MessageEvent('message', { origin, source, data: {
      type: 'ALERTIFY_AI_CHAT_STATE', kind, pairId, user: 'realm:one', session: 'session-one', sequence: 1, ...extra,
    } }));
  }

  function connectParent(): void {
    windows.start(false, callbacks);
    windows.detach();
    deliver('READY');
    deliver('ACK');
  }

  function connectChild(): void {
    Object.defineProperty(window, 'opener', { configurable: true, value: peer });
    history.replaceState(null, '', '/ai-chat?handoff=child-pair');
    expect(windows.start(true, callbacks)).toBe(true);
    deliver('INIT', { snapshot: state.snapshot() });
  }

  it('waits for the popup acknowledgement before hiding the original panel', () => {
    windows.start(false, callbacks);
    windows.detach();
    expect(windows.transferring()).toBe(true);
    expect(windows.detached()).toBe(false);
    deliver('READY');
    expect(peer.postMessage).toHaveBeenCalledWith(expect.objectContaining({ kind: 'INIT', snapshot: expect.objectContaining({ selectedId: 1 }) }), location.origin);
    expect(callbacks.detached).not.toHaveBeenCalled();
    deliver('ACK');
    expect(windows.detached()).toBe(true);
    expect(callbacks.detached).toHaveBeenCalledOnce();
    expect(windows.transferring()).toBe(false);
  });

  it('rejects messages from another origin, window, account, session or pair', () => {
    connectParent();
    const original = state.snapshot();
    const snapshot = { ...original, drafts: original.drafts.map(entry => ({ ...entry, draft: { ...entry.draft, message: 'wrong-source draft' } })) };
    deliver('RETURN', { snapshot }, 'https://other.test');
    deliver('RETURN', { snapshot }, location.origin, window);
    deliver('RETURN', { snapshot, user: 'realm:two' });
    deliver('RETURN', { snapshot, session: 'other-session' });
    deliver('RETURN', { snapshot, pairId: 'another-pair' });
    expect(state.read(1).message).toBe('panel draft');
    expect(callbacks.restore).not.toHaveBeenCalled();
  });

  it('keeps the latest popup edits for closing with X and ignores older revisions', () => {
    connectParent();
    const snapshot = structuredClone(state.snapshot());
    const changed = { ...snapshot, drafts: snapshot.drafts.map(entry => ({ ...entry, draft: { ...entry.draft, message: 'popup edits' } })) };
    deliver('UPDATE', { sequence: 2, snapshot: changed });
    deliver('UPDATE', { sequence: 1, snapshot });
    peer.closed = true;
    vi.advanceTimersByTime(250);
    expect(state.read(1).message).toBe('popup edits');
    expect(windows.detached()).toBe(false);
    expect(callbacks.restore).toHaveBeenCalledOnce();
  });

  it('confirms a return before releasing the popup and preserves all drafts', () => {
    connectParent();
    state.capture(null, 'unsent new chat', 'DIRECT', 'AUTO');
    deliver('RETURN', { sequence: 2, snapshot: state.snapshot() });
    expect(peer.postMessage).toHaveBeenCalledWith(expect.objectContaining({ kind: 'RETURNED' }), location.origin);
    expect(callbacks.restore).toHaveBeenCalledOnce();
    expect(state.read(null).message).toBe('unsent new chat');
  });

  it('keeps the panel draft on popup blocking or an initial handoff timeout', () => {
    windows.start(false, callbacks);
    vi.mocked(window.open).mockReturnValueOnce(null);
    windows.detach();
    expect(windows.error()).toBe('ai.chat.transferFailed');
    windows.detach();
    vi.advanceTimersByTime(15000);
    expect(peer.close).toHaveBeenCalledOnce();
    expect(state.read(1).message).toBe('panel draft');
    expect(windows.detached()).toBe(false);
    expect(windows.transferring()).toBe(false);
  });

  it('restores the child before acknowledging, publishes edits, and waits for the return acknowledgement', () => {
    const close = vi.spyOn(window, 'close').mockImplementation(() => {});
    connectChild();
    expect(callbacks.restore).toHaveBeenCalledOnce();
    expect(peer.postMessage).toHaveBeenCalledWith(expect.objectContaining({ kind: 'ACK' }), location.origin);
    state.capture(1, 'child edits', 'DIRECT', 'AUTO');
    windows.publish();
    expect(peer.postMessage).toHaveBeenCalledWith(expect.objectContaining({ kind: 'UPDATE' }), location.origin);
    windows.reattach();
    expect(close).not.toHaveBeenCalled();
    deliver('RETURNED');
    expect(close).toHaveBeenCalledOnce();
  });

  it('publishes the final state on pagehide and does not close if the opener is unavailable', () => {
    const close = vi.spyOn(window, 'close').mockImplementation(() => {});
    connectChild();
    window.dispatchEvent(new Event('pagehide'));
    expect(peer.postMessage).toHaveBeenCalledWith(expect.objectContaining({ kind: 'CLOSE' }), location.origin);
    peer.closed = true;
    windows.reattach();
    expect(windows.error()).toBe('ai.chat.mainWindowUnavailable');
    expect(close).not.toHaveBeenCalled();
  });
});
