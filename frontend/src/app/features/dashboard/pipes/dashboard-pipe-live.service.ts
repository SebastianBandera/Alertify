import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, effect, inject, Injectable, signal, Signal } from '@angular/core';
import { Observable } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { AdminEventChannelService } from '../../../core/realtime/admin-event-channel.service';
import { ViewerEventChannelService } from '../../../core/realtime/viewer-event-channel.service';
import { PageResponse } from '../../../core/api/configuration-api.service';
import { LocalizationService } from '../../../core/i18n/localization.service';
import { AlertMessageService } from '../../../core/alert-messages/alert-message.service';
import { readStoredViewSettings } from '../dashboard-view-settings';
import { DashboardPipeCard, pipeIdentity, pipeState } from './dashboard-pipe';

interface Mute { readonly kind: 'ignored' | 'silenced' | 'state'; readonly identity?: string; }
const STORAGE = 'alertify.dashboard.pipes.muted';
interface PipeEventPayloads { DASHBOARD_PIPE: DashboardPipeCard; DASHBOARD_PIPE_REMOVED: { readonly pipeId: number }; }
interface PipeChannel {
  readonly connectionState: Signal<'idle' | 'connecting' | 'connected' | 'reconnecting'>;
  on<Name extends keyof PipeEventPayloads>(name: Name): Observable<PipeEventPayloads[Name]>;
  request<T>(name: string, payload: unknown): Promise<T>;
}

@Injectable({ providedIn: 'root' })
export class DashboardPipeLiveService {
  private readonly auth = inject(AuthService);
  private readonly admin = inject(AdminEventChannelService);
  private readonly viewer = inject(ViewerEventChannelService);
  private readonly channel: PipeChannel = this.auth.isAdmin ? this.admin : this.viewer;
  private readonly destroyRef = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly localization = inject(LocalizationService);
  private readonly messages = inject(AlertMessageService);
  private readonly byId = signal<ReadonlyMap<number, DashboardPipeCard>>(new Map());
  readonly cards = computed(() => [...this.byId().values()]);
  readonly muted = signal<Readonly<Record<string, Mute>>>(this.read());
  readonly loading = signal(true);
  readonly error = signal(false);
  private readonly terminal = new Map<number, string | null>();
  private readonly removedIds = new Set<number>();
  private generation = 0;
  private seen: Set<number> | null = null;

  constructor() {
    this.channel.on('DASHBOARD_PIPE').pipe(takeUntilDestroyed(this.destroyRef)).subscribe(card => this.apply(card, true));
    this.channel.on('DASHBOARD_PIPE_REMOVED').pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ pipeId }) => {
      // Database identities are never reused; a pending older page must not resurrect a deleted Pipe.
      this.removedIds.add(pipeId);
      this.byId.update(cards => { const next = new Map(cards); next.delete(pipeId); return next; });
      this.terminal.delete(pipeId);
    });
    effect(() => { if (this.channel.connectionState() === 'connected') void this.snapshot(); });
    const storage = (event: StorageEvent) => { if (event.key === STORAGE || event.key === null) this.muted.set(this.read()); };
    this.document.defaultView?.addEventListener('storage', storage);
    this.destroyRef.onDestroy(() => this.document.defaultView?.removeEventListener('storage', storage));
  }

  private read(): Readonly<Record<string, Mute>> {
    try {
      const value: unknown = JSON.parse(this.document.defaultView?.localStorage.getItem(STORAGE) ?? '{}');
      if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
      return Object.fromEntries(Object.entries(value).filter(([id, item]) => /^\d+$/.test(id) && item && typeof item === 'object'
        && ['ignored', 'silenced', 'state'].includes(item.kind) && (item.kind !== 'state' || typeof item.identity === 'string')));
    } catch { return {}; }
  }

  mute(card: DashboardPipeCard, kind: Mute['kind'] | null): void {
    const next = { ...this.muted() };
    if (kind === null) delete next[card.id];
    else next[card.id] = { kind, ...(kind === 'state' ? { identity: this.identity(card) } : {}) };
    this.muted.set(next);
    try { this.document.defaultView?.localStorage.setItem(STORAGE, JSON.stringify(next)); } catch { /* In-memory preferences still apply. */ }
  }

  private async snapshot(): Promise<void> {
    const generation = ++this.generation;
    const seen = new Set<number>(); this.seen = seen; this.loading.set(true); this.error.set(false);
    try {
      for (let page = 0; ; page++) {
        const result = await this.channel.request<PageResponse<DashboardPipeCard>>('DASHBOARD_PIPE_PAGE', { page, size: 12 });
        if (generation !== this.generation) return;
        for (const card of result.content) { seen.add(card.id); this.apply(card, false); }
        if (page + 1 >= result.page.totalPages) break;
      }
      this.byId.update(cards => new Map([...cards].filter(([id]) => seen.has(id))));
    } catch { this.error.set(true); }
    finally { if (generation === this.generation) { this.loading.set(false); this.seen = null; } }
  }

  private apply(card: DashboardPipeCard, event: boolean): void {
    if (this.removedIds.has(card.id)) return;
    this.seen?.add(card.id);
    const previous = this.byId().get(card.id);
    if (previous && Date.parse(previous.observedAt) > Date.parse(card.observedAt)) return;
    this.byId.update(cards => new Map(cards).set(card.id, card));
    const execution = card.execution;
    if (!execution || execution.status === 'RUNNING') return;
    const mute = this.muted()[card.id];
    const released = mute?.kind === 'state' && mute.identity !== this.identity(card);
    if (released || (mute?.kind === 'silenced' && execution.outcome === 'SUCCESS')) this.mute(card, null);
    const previousOutcome = this.terminal.get(card.id);
    this.terminal.set(card.id, execution.outcome);
    const changed = previousOutcome !== execution.outcome;
    const recent = execution.finishedAt !== null && Date.now() - Date.parse(execution.finishedAt) < 120_000;
    if (event && recent && (changed || released) && !this.muted()[card.id] && (execution.outcome === 'WARN' || execution.outcome === 'ERROR')) this.notify(card);
  }

  private identity(card: DashboardPipeCard): string {
    return pipeIdentity(card, step => step.alertExecution && step.templateKey
      ? this.messages.presentation(step.templateKey, step.alertExecution).identity : '');
  }

  private notify(card: DashboardPipeCard): void {
    const settings = readStoredViewSettings();
    if (settings.hiddenStates.includes(pipeState(card))) return;
    if (card.tags.length && card.tags.every(tag => settings.hiddenTagIds.includes(tag.id))) return;
    if (this.document.visibilityState === 'visible' && this.document.hasFocus() && this.router.url.startsWith('/dashboard')) return;
    const window = this.document.defaultView;
    if (!window || !('Notification' in window) || window.Notification.permission !== 'granted') return;
    const notification = new window.Notification(card.name, {
      body: this.localization.translateDynamic('dashboard.pipe.' + card.execution?.outcome), tag: 'alertify-pipe-' + card.id, icon: 'icons/alertify.svg',
    });
    notification.onclick = () => { window.focus(); notification.close(); void this.router.navigate(['/dashboard'], { queryParams: { pipe: card.id } }); };
  }
}
