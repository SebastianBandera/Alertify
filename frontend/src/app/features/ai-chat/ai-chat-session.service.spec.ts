import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { AiChatSessionService } from './ai-chat-session.service';

describe('AiChatSessionService', () => {
  let state: AiChatSessionService;
  let auth: { userIdentifier: string; sessionIdentifier: string };
  beforeEach(() => {
    auth = { userIdentifier: 'realm:one', sessionIdentifier: 'session-one' };
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: auth }] });
    state = TestBed.inject(AiChatSessionService);
  });

  it('keeps independent drafts and local options for each conversation and the new-chat slot', () => {
    state.select(null);
    state.capture(null, 'new\nmessage', 'PLAN', 'ASK');
    state.select(1);
    state.capture(1, 'first', 'DIRECT', 'AUTO');
    state.select(2);
    expect(state.read(2).message).toBe('');
    expect(state.select(1)).toMatchObject({ message: 'first', mode: 'DIRECT', approvalPolicy: 'AUTO' });
    expect(state.select(null)).toMatchObject({ message: 'new\nmessage', mode: 'PLAN' });
    state.resetNew();
    expect(state.read(null).message).toBe('');
    expect(state.read(1).message).toBe('first');
  });

  it('uses server options until the user changes them, without overwriting a draft on refresh', () => {
    state.seed(1, 'PLAN', 'ASK');
    state.capture(1, 'draft', 'PLAN', 'ASK');
    expect(state.seed(1, 'READ_ONLY', 'ASK')).toMatchObject({ message: 'draft', mode: 'READ_ONLY' });
    state.capture(1, 'draft', 'DIRECT', 'AUTO');
    expect(state.seed(1, 'READ_ONLY', 'ASK')).toMatchObject({ message: 'draft', mode: 'DIRECT', approvalPolicy: 'AUTO' });
  });

  it('clears only the submitted revision and migrates a new draft to its server conversation', () => {
    state.select(null);
    const submitted = state.capture(null, 'sent', 'PLAN', 'ASK');
    state.capture(null, 'written while sending', 'PLAN', 'ASK');
    state.finishSubmission(null, submitted, 1);
    expect(state.select(1).message).toBe('written while sending');
    expect(state.select(null).message).toBe('');
    const next = state.capture(1, 'next', 'PLAN', 'ASK');
    state.finishSubmission(1, next, 1);
    expect(state.read(1).message).toBe('');
  });

  it('does not clear a new draft created while an older submission was pending', () => {
    const submitted = state.capture(null, 'old', 'READ_ONLY', 'ASK');
    state.resetNew();
    state.capture(null, 'replacement', 'PLAN', 'ASK');
    state.finishSubmission(null, submitted, 1);
    expect(state.read(null).message).toBe('replacement');
  });

  it('round-trips a snapshot in memory without writing the composer to browser storage', () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    state.select(1);
    state.capture(1, 'private draft', 'DIRECT', 'AUTO');
    const snapshot = structuredClone(state.snapshot());
    state.remove(1);
    expect(state.replace(snapshot)).toBe(true);
    expect(state.read(1)).toMatchObject({ message: 'private draft', mode: 'DIRECT' });
    expect(local).not.toHaveBeenCalled();
    local.mockRestore();
  });

  it.each(['userIdentifier', 'sessionIdentifier'] as const)('isolates state after a change of %s', field => {
    state.select(1);
    state.capture(1, 'previous user or session', 'DIRECT', 'AUTO');
    auth[field] = 'another';
    expect(state.hasSelection).toBe(false);
    expect(state.read(1)).toMatchObject({ message: '', mode: 'READ_ONLY', approvalPolicy: 'ASK' });
  });

  it('rejects malformed snapshots atomically', () => {
    state.select(1);
    state.capture(1, 'keep me', 'PLAN', 'ASK');
    const snapshot = state.snapshot();
    for (const invalid of [null, { ...snapshot, version: 2 }, { ...snapshot, selectedId: -1 }, { ...snapshot, drafts: [] },
      { ...snapshot, drafts: [{ conversationId: 1, draft: { ...snapshot.drafts[0].draft, mode: 'INVALID' } }] },
      { ...snapshot, drafts: [{ conversationId: 1, draft: { ...snapshot.drafts[0].draft, message: 'x'.repeat(32769) } }] }]) {
      expect(state.replace(invalid)).toBe(false);
      expect(state.read(1).message).toBe('keep me');
    }
  });
});
