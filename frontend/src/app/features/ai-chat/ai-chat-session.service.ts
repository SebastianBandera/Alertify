import { Injectable, inject } from '@angular/core';

import { AiApprovalPolicy, AiChatMode } from '../../core/api/ai-chat-api.service';
import { AuthService } from '../../core/auth/auth.service';

export interface ChatDraft {
  readonly draftId: string;
  readonly messageRevision: number;
  readonly message: string;
  readonly mode: AiChatMode;
  readonly approvalPolicy: AiApprovalPolicy;
  readonly optionsEdited: boolean;
}

export interface ChatSessionSnapshot {
  readonly version: 1;
  readonly hasSelection: boolean;
  readonly selectedId: number | null;
  readonly drafts: readonly { readonly conversationId: number | null; readonly draft: ChatDraft }[];
}

function validId(value: unknown): value is number | null {
  return value === null || (typeof value === 'number' && Number.isSafeInteger(value) && value > 0);
}

/** Composer text lives only in memory. Server conversation details are never part of a snapshot. */
@Injectable({ providedIn: 'root' })
export class AiChatSessionService {
  private readonly auth = inject(AuthService);
  private owner = '';
  private drafts = new Map<number | null, ChatDraft>();
  private selection: number | null = null;
  private initialized = false;

  get hasSelection(): boolean { this.checkOwner(); return this.initialized; }
  get selectedId(): number | null { this.checkOwner(); return this.selection; }

  select(id: number | null): ChatDraft {
    this.checkOwner();
    this.selection = id;
    this.initialized = true;
    return this.read(id);
  }

  read(id: number | null): ChatDraft {
    this.checkOwner();
    let draft = this.drafts.get(id);
    if (!draft) {
      draft = { draftId: crypto.randomUUID(), messageRevision: 0, message: '', mode: 'READ_ONLY', approvalPolicy: 'ASK', optionsEdited: false };
      this.drafts.set(id, draft);
    }
    return draft;
  }

  capture(id: number | null, message: string, mode: AiChatMode, approvalPolicy: AiApprovalPolicy): ChatDraft {
    const previous = this.read(id);
    const draft = { ...previous, message, mode, approvalPolicy,
      messageRevision: previous.messageRevision + (message === previous.message ? 0 : 1),
      optionsEdited: previous.optionsEdited || mode !== previous.mode || approvalPolicy !== previous.approvalPolicy };
    this.drafts.set(id, draft);
    return draft;
  }

  seed(id: number, mode: AiChatMode, approvalPolicy: AiApprovalPolicy): ChatDraft {
    const previous = this.read(id);
    if (previous.optionsEdited) return previous;
    const draft = { ...previous, mode, approvalPolicy };
    this.drafts.set(id, draft);
    return draft;
  }

  resetNew(): ChatDraft {
    this.checkOwner();
    this.drafts.delete(null);
    return this.select(null);
  }

  remove(id: number): void {
    this.checkOwner();
    this.drafts.delete(id);
  }

  finishSubmission(id: number | null, submitted: ChatDraft, conversationId: number): void {
    this.checkOwner();
    const current = this.drafts.get(id);
    if (!current || current.draftId !== submitted.draftId) return;
    const draft = current.messageRevision === submitted.messageRevision
      ? { ...current, message: '', messageRevision: current.messageRevision + 1 } : current;
    this.drafts.set(conversationId, draft);
    if (id === null) this.drafts.delete(null);
  }

  snapshot(): ChatSessionSnapshot {
    this.checkOwner();
    return { version: 1, hasSelection: this.initialized, selectedId: this.selection,
      drafts: Array.from(this.drafts, ([conversationId, draft]) => ({ conversationId, draft: { ...draft } })) };
  }

  replace(value: unknown): boolean {
    this.checkOwner();
    if (!value || typeof value !== 'object') return false;
    const snapshot = value as ChatSessionSnapshot;
    if (snapshot.version !== 1 || typeof snapshot.hasSelection !== 'boolean' || !validId(snapshot.selectedId) || !Array.isArray(snapshot.drafts)) return false;
    const drafts = new Map<number | null, ChatDraft>();
    for (const entry of snapshot.drafts) {
      if (!entry || !validId(entry.conversationId) || drafts.has(entry.conversationId)) return false;
      const draft = entry.draft;
      if (!draft || typeof draft.draftId !== 'string' || !draft.draftId || !Number.isSafeInteger(draft.messageRevision) || draft.messageRevision < 0
        || typeof draft.message !== 'string' || draft.message.length > 32768 || typeof draft.optionsEdited !== 'boolean'
        || !['READ_ONLY', 'PLAN', 'DIRECT'].includes(draft.mode) || !['ASK', 'AUTO'].includes(draft.approvalPolicy)) return false;
      drafts.set(entry.conversationId, { draftId: draft.draftId, messageRevision: draft.messageRevision, message: draft.message,
        mode: draft.mode, approvalPolicy: draft.approvalPolicy, optionsEdited: draft.optionsEdited });
    }
    if (snapshot.hasSelection && !drafts.has(snapshot.selectedId)) return false;
    this.drafts = drafts;
    this.selection = snapshot.selectedId;
    this.initialized = snapshot.hasSelection;
    return true;
  }

  private checkOwner(): void {
    const owner = `${this.auth.userIdentifier}\0${this.auth.sessionIdentifier ?? ''}`;
    if (owner === this.owner) return;
    this.owner = owner;
    this.drafts.clear();
    this.selection = null;
    this.initialized = false;
  }
}
