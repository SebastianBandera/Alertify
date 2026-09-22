import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, effect, inject, Injectable, Injector, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';

import { PageResponse } from '../../core/api/configuration-api.service';
import { BrowserNotificationService } from '../../core/notifications/browser-notification.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { DashboardAlertCard } from './dashboard-card';
import { hasVisibleTag, readStoredViewSettings } from './dashboard-view-settings';

const PAGE_SIZE = 12;
const PAGE_REQUEST = 'DASHBOARD_PAGE';
/* A result older than this is not news, only an alert we are seeing for the first time. */
const RECENT_RESULT_MILLIS = 2 * 60_000;

export interface DashboardChange {
  readonly kind: 'page' | 'update' | 'removal';
  readonly alertId: number | null;
  /** For updates: the last result's status (or "never ran") differs from what we held before. */
  readonly stateChanged: boolean;
}

function resultStatus(card: DashboardAlertCard | undefined): string {
  return card?.lastExecution?.status ?? 'NONE';
}

/**
 * Live copy of the board, fed by the administrative event channel: a paged
 * snapshot on every (re)connection plus one full tile per change afterwards.
 * It lives at the root so tiles keep arriving while the user is elsewhere,
 * and WARN/ERROR results still raise a browser notification.
 */
@Injectable({ providedIn: 'root' })
export class DashboardLiveService {
  private readonly channel = inject(AdminEventChannelService);
  private readonly notifications = inject(BrowserNotificationService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly cardsById = signal<ReadonlyMap<number, DashboardAlertCard>>(new Map());
  readonly cards = computed(() => [...this.cardsById().values()]);
  readonly loading = signal(false);
  readonly totalCards = signal<number | null>(null);
  readonly loadedFromPages = signal(0);
  /** Emitted right after the state changed, before the DOM re-rendered, so views can capture layout first. */
  readonly changes$ = new Subject<DashboardChange>();
  /* Position of each card inside the page it arrived with, for the staggered entrance. */
  private readonly arrivalOffsets = new Map<number, number>();
  private snapshotIds: Set<number> | null = null;
  private snapshotRun = 0;
  private started = false;

  start(): void {
    if (this.started) return;
    this.started = true;

    effect(() => {
      if (this.channel.connectionState() === 'connected') void this.loadSnapshot();
    }, { injector: this.injector });
    this.channel.on('DASHBOARD_ALERT').pipe(takeUntilDestroyed(this.destroyRef)).subscribe((card) => this.applyEvent(card));
    this.channel.on('DASHBOARD_ALERT_REMOVED').pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ alertId }) => this.remove(alertId));
  }

  arrivalOffset(alertId: number): number {
    return this.arrivalOffsets.get(alertId) ?? 0;
  }

  private async loadSnapshot(): Promise<void> {
    const run = ++this.snapshotRun;
    const seen = new Set<number>();
    this.snapshotIds = seen;
    this.loading.set(true);
    this.loadedFromPages.set(0);
    try {
      for (let pageNumber = 0; ; pageNumber++) {
        const page = await this.channel.request<PageResponse<DashboardAlertCard>>(PAGE_REQUEST, { page: pageNumber, size: PAGE_SIZE });
        if (run !== this.snapshotRun) return;
        page.content.forEach((card, index) => {
          seen.add(card.alert.id);
          this.arrivalOffsets.set(card.alert.id, index);
        });
        this.cardsById.update((cards) => {
          const next = new Map(cards);
          for (const card of page.content) next.set(card.alert.id, card);
          return next;
        });
        this.totalCards.set(page.page.totalElements);
        this.loadedFromPages.update((count) => count + page.content.length);
        this.changes$.next({ kind: 'page', alertId: null, stateChanged: false });
        if (pageNumber + 1 >= page.page.totalPages) break;
      }
      /* Anything we still hold that the snapshot did not mention was deleted while we were away. */
      this.cardsById.update((cards) => new Map([...cards].filter(([id]) => seen.has(id))));
    } catch {
      // The channel reconnects on its own and a fresh snapshot follows; the last known board stays visible.
    } finally {
      if (run === this.snapshotRun) {
        this.snapshotIds = null;
        this.loading.set(false);
      }
    }
  }

  private applyEvent(card: DashboardAlertCard): void {
    const alertId = card.alert.id;
    const previous = this.cardsById().get(alertId);
    this.snapshotIds?.add(alertId);
    this.cardsById.update((cards) => new Map(cards).set(alertId, card));
    this.changes$.next({ kind: 'update', alertId, stateChanged: resultStatus(previous) !== resultStatus(card) });
    if (this.isNewResult(previous, card)) this.notify(card);
  }

  private remove(alertId: number): void {
    if (!this.cardsById().has(alertId)) return;
    this.cardsById.update((cards) => {
      const next = new Map(cards);
      next.delete(alertId);
      return next;
    });
    this.changes$.next({ kind: 'removal', alertId, stateChanged: false });
  }

  /**
   * A result is news when its execution differs from the one we knew for that
   * alert. For an alert we have not loaded yet (mid-snapshot, or just created)
   * only a result that finished moments ago counts, so a "started" tile that
   * merely repeats an old failure stays quiet.
   */
  private isNewResult(previous: DashboardAlertCard | undefined, card: DashboardAlertCard): boolean {
    const execution = card.lastExecution;
    if (execution === null || execution.status === 'SUCCESS') return false;
    if (previous) return previous.lastExecution?.executionId !== execution.executionId;
    return Date.now() - Date.parse(execution.finishedAt) < RECENT_RESULT_MILLIS;
  }

  /*
   * The board itself already pulses the changed tile, so only notify when it is not in front of the user.
   * An alert the board hides because all its tags are hidden stays quiet too; the stored settings are read
   * each time so the latest ribbon choice applies, even one made in another tab.
   */
  private notify(card: DashboardAlertCard): void {
    if (!hasVisibleTag(card, readStoredViewSettings().hiddenTagIds)) return;
    const boardVisible = this.document.visibilityState === 'visible' && this.router.url.startsWith('/dashboard');
    if (!boardVisible) this.notifications.notifyAlert(card);
  }
}
