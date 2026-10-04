import { inject, Injectable, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

export type EditorDraftKind = 'alerts' | 'procedures' | 'pipes' | 'hooks';
interface EditorDraft { readonly form: unknown; readonly editing: unknown; readonly savedAt: number; }
const KINDS: readonly EditorDraftKind[] = ['alerts', 'procedures', 'pipes', 'hooks'];
const MAX_AGE = 7 * 86_400_000;

/** Inline parameter text stays in this session; storage contains only safe metadata and references. */
function safeStoredValue(value: unknown, key = '', persistent = true): unknown {
  if (value instanceof Blob) return null;
  if (key === 'textValue') return persistent ? '' : value;
  if (/password|credential|token$|secretValue|authorization|file|script|body/i.test(key)) return null;
  if (Array.isArray(value)) return value.map((item) => safeStoredValue(item, '', persistent));
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    const sanitized = Object.fromEntries(Object.entries(object).map(([name, item]) => [name, safeStoredValue(item, name, persistent)]));
    if (persistent && object['source'] === 'TEXT') sanitized['configured'] = false;
    return sanitized;
  }
  return value;
}

@Injectable({ providedIn: 'root' })
export class EditorDraftService {
  private readonly auth = inject(AuthService);
  private readonly drafts = new Map<EditorDraftKind, EditorDraft>();
  readonly kinds = signal<readonly EditorDraftKind[]>([]);
  readonly requestedRestore = signal<EditorDraftKind | null>(null);

  constructor() {
    for (const kind of KINDS) {
      try {
        const stored = JSON.parse(localStorage.getItem(this.key(kind)) ?? 'null') as EditorDraft | null;
        if (stored && typeof stored.savedAt === 'number' && Date.now() - stored.savedAt < MAX_AGE
          && stored.form && typeof stored.form === 'object') this.drafts.set(kind, stored);
        else localStorage.removeItem(this.key(kind));
      } catch { /* Browser storage is optional. */ }
    }
    this.refresh();
  }

  has(kind: EditorDraftKind): boolean { return this.kinds().includes(kind); }

  save(kind: EditorDraftKind, form: unknown, editing: unknown): void {
    const resource = editing as { id: number; version: number; name: string } | null;
    const draft = { form: safeStoredValue(form, '', false), editing: resource ? { id: resource.id, version: resource.version, name: resource.name } : null, savedAt: Date.now() };
    this.drafts.set(kind, draft);
    try { localStorage.setItem(this.key(kind), JSON.stringify(safeStoredValue(draft))); } catch { /* Keep the in-memory draft. */ }
    this.refresh();
  }

  read<T, E>(kind: EditorDraftKind): { form: T; editing: E | null } | null {
    const draft = this.drafts.get(kind);
    return draft ? structuredClone(draft) as { form: T; editing: E | null } : null;
  }

  remove(kind: EditorDraftKind): void {
    this.drafts.delete(kind);
    try { localStorage.removeItem(this.key(kind)); } catch { /* Browser storage is optional. */ }
    this.refresh();
  }

  private refresh(): void { this.kinds.set(KINDS.filter((kind) => this.drafts.has(kind))); }
  private key(kind: EditorDraftKind): string { return `alertify.editor-draft.v1.${this.auth.userIdentifier}.${kind}`; }
}
