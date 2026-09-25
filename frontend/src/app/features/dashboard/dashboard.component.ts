import { DatePipe, NgTemplateOutlet } from '@angular/common';
import {
  afterNextRender,
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AlertMessageService } from '../../core/alert-messages/alert-message.service';
import { AlertApiService, AlertExecution, AlertExecutionStatus, AlertTag } from '../../core/api/alert-api.service';
import { ApiRequestError } from '../../core/api/configuration-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionActionsService } from '../../core/auth/session-actions.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { TranslationKey } from '../../core/i18n/localization.types';
import { DragScrollDirective } from '../../shared/drag-scroll/drag-scroll.directive';
import { DashboardAcknowledgementService } from './dashboard-acknowledgement.service';
import {
  compareDashboardCards,
  DashboardAlertCard,
  effectiveStatus,
  PendingIssue,
  stateChangedAt,
} from './dashboard-card';
import { DashboardDeck } from './dashboard-deck';
import { DashboardChange, DashboardLiveService } from './dashboard-live.service';
import { DashboardMuteService } from './dashboard-mute.service';
import { CardState, hasVisibleTag, readStoredViewSettings, storeViewSettings } from './dashboard-view-settings';
import { DashboardConnectionOverlayComponent } from './connection-overlay/dashboard-connection-overlay.component';
import { DashboardRibbonComponent } from './ribbon/dashboard-ribbon.component';

type StabilityState = 'stable' | 'warn' | 'error';
/* How long the alert has been green, from a state change within the hour to a week or more. */
type SuccessAge = 'very-fresh' | 'fresh' | 'recent' | 'aging' | 'old' | 'very-old';

interface Stability {
  readonly state: StabilityState;
  readonly label: string;
  /** Tooltip; the look-back window description when absent. */
  readonly title?: string;
}

/** The context menu opened on a card, at the pointer (or the card corner from the keyboard). */
interface CardMenu {
  readonly alertId: number;
  readonly x: number;
  readonly y: number;
}

interface DashboardNotice {
  readonly message: string;
  readonly error: boolean;
}

const SEVERITY: Readonly<Record<AlertExecutionStatus, number>> = { SUCCESS: 0, WARN: 1, ERROR: 2 };
const HOUR_MILLIS = 3_600_000;
const DAY_MILLIS = 24 * HOUR_MILLIS;
/* Placeholders shown before the first page reveals how many alerts exist. */
const INITIAL_SKELETONS = 4;
/* Cards of one page enter one after the other rather than all at once. */
const ENTER_STAGGER_MILLIS = 90;
/* A changed card fades to its new colour and pulses before anything moves. */
const CHANGE_HIGHLIGHT_MILLIS = 1_200;
const MOVE_DELAY_MILLIS = 700;
const MOVE_DURATION_MILLIS = 500;
/* Space kept between the card menu and the viewport edges. */
const MENU_MARGIN_PIXELS = 8;
/* Where a keyboard-opened menu lands, measured from the card's top-left corner. */
const MENU_KEYBOARD_OFFSET_PIXELS = 16;
const NOTICE_MILLIS = 6_000;

function startOfDay(timestamp: number): number {
  const date = new Date(timestamp);
  date.setHours(0, 0, 0, 0);
  return date.getTime();
}

function calendarDaysBetween(from: number, to: number): number {
  return Math.round((startOfDay(to) - startOfDay(from)) / DAY_MILLIS);
}

const CARD_STATES: Readonly<Record<AlertExecutionStatus, CardState>> = {
  SUCCESS: 'success',
  WARN: 'warn',
  ERROR: 'error',
};

const STATUS_LABEL_KEYS: Readonly<Record<AlertExecutionStatus, TranslationKey>> = {
  SUCCESS: 'dashboard.status.SUCCESS',
  WARN: 'dashboard.status.WARN',
  ERROR: 'dashboard.status.ERROR',
};

/* A worse status seen inside the window that the alert already recovered from. */
const INCIDENT_LABEL_KEYS: Readonly<Record<'WARN' | 'ERROR', TranslationKey>> = {
  WARN: 'dashboard.card.incident.WARN',
  ERROR: 'dashboard.card.incident.ERROR',
};

const PENDING_LABEL_KEYS: Readonly<Record<'WARN' | 'ERROR', TranslationKey>> = {
  WARN: 'dashboard.pending.WARN',
  ERROR: 'dashboard.pending.ERROR',
};

/* The current non-success status, still ongoing since the streak started. */
const ONGOING_LABEL_KEYS: Readonly<Record<'WARN' | 'ERROR', TranslationKey>> = {
  WARN: 'dashboard.card.ongoing.WARN',
  ERROR: 'dashboard.card.ongoing.ERROR',
};

const STABILITY_STATES: Readonly<Record<'WARN' | 'ERROR', StabilityState>> = { WARN: 'warn', ERROR: 'error' };

type ExecutionTrigger = NonNullable<AlertExecution['trigger']>;

const TRIGGER_LABEL_KEYS: Readonly<Record<ExecutionTrigger, TranslationKey>> = {
  CRON: 'dashboard.detail.trigger.CRON',
  MANUAL: 'dashboard.detail.trigger.MANUAL',
  HOOK: 'dashboard.detail.trigger.HOOK',
};

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, FormsModule, NgTemplateOutlet, DragScrollDirective, DashboardRibbonComponent, DashboardConnectionOverlayComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent {
  protected readonly localization = inject(LocalizationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly live = inject(DashboardLiveService);
  private readonly authService = inject(AuthService);
  private readonly sessionActions = inject(SessionActionsService);
  private readonly alertApi = inject(AlertApiService);
  private readonly alertMessages = inject(AlertMessageService);
  protected readonly mute = inject(DashboardMuteService);
  protected readonly isAdmin = this.authService.isAdmin;
  protected readonly canRunFromDashboard = this.authService.canRunFromDashboard;
  protected readonly cards = this.live.cards;
  /* Cards whose state just changed, while their highlight animation plays. */
  protected readonly changedIds = signal<ReadonlySet<number>>(new Set());
  protected readonly acknowledgements = inject(DashboardAcknowledgementService);
  private readonly pendingOf = (card: DashboardAlertCard): PendingIssue | null => this.acknowledgements.pending(card);
  protected readonly sortedCards = computed(() => [...this.cards()].sort((left, right) => compareDashboardCards(left, right, this.pendingOf)));
  protected readonly settings = signal(readStoredViewSettings());
  protected readonly availableTags = computed<readonly AlertTag[]>(() => {
    const tags = new Map<number, AlertTag>();
    for (const card of this.cards()) for (const tag of card.alert.tags) tags.set(tag.id, tag);
    return [...tags.values()].sort((left, right) => left.name.localeCompare(right.name));
  });
  protected readonly visibleCards = computed(() => {
    const { hiddenStates, hiddenTagIds } = this.settings();
    return this.sortedCards().filter((card) => !hiddenStates.includes(this.cardState(card)) && hasVisibleTag(card, hiddenTagIds));
  });
  /* Ignored and silenced alerts leave the regular flow and always close the board as their own deck. */
  private readonly activeCards = computed(() => this.visibleCards().filter((card) => !this.mute.isMuted(card.alert.id)));
  /* Silenced alerts stay in the muted deck; ignored ones can be left out of the board altogether. */
  protected readonly mutedCards = computed(() => this.visibleCards().filter((card) =>
    this.mute.isMuted(card.alert.id) && !(this.settings().hideIgnored && this.mute.isIgnored(card.alert.id))));
  /* Greens are dealt as a deck unless ungrouped: worse states first, the deck row, then never-executed. */
  protected readonly deckCards = computed(() =>
    this.settings().ungroupGreens ? [] : this.activeCards().filter((card) => this.cardState(card) === 'success'));
  protected readonly leadingCards = computed(() =>
    this.deckCards().length === 0
      ? this.activeCards()
      : this.activeCards().filter((card) => this.cardState(card) === 'error' || this.cardState(card) === 'warn'));
  protected readonly trailingCards = computed(() =>
    this.deckCards().length === 0 ? [] : this.activeCards().filter((card) => this.cardState(card) === 'none'));
  private readonly deckWidth = signal(0);
  private readonly deckCardWidth = signal(0);
  protected readonly greenDeck = new DashboardDeck(this.deckCards, this.deckWidth, this.deckCardWidth);
  protected readonly mutedDeck = new DashboardDeck(this.mutedCards, this.deckWidth, this.deckCardWidth);
  private readonly decks = viewChildren<ElementRef<HTMLElement>>('deck');
  private readonly grid = viewChild.required<ElementRef<HTMLElement>>('grid');
  protected readonly totalCards = this.live.totalCards;
  protected readonly loading = this.live.loading;
  /* Placeholders for the tiles still to come: the rest of the snapshot, or the whole board while the channel connects. */
  protected readonly skeletons = computed(() => {
    const total = this.totalCards();
    let pending = 0;
    if (this.loading()) pending = total === null ? INITIAL_SKELETONS : total - this.live.loadedFromPages();
    else if (this.cards().length === 0 && this.live.connectionState() !== 'connected') pending = INITIAL_SKELETONS;
    return Array.from({ length: Math.max(0, pending) }, (_, index) => index);
  });
  protected readonly selectedCard = signal<DashboardAlertCard | null>(null);
  private readonly detailDialog = viewChild<ElementRef<HTMLElement>>('detailDialog');
  protected readonly cardMenu = signal<CardMenu | null>(null);
  /* The live tile behind the open menu; the menu closes by itself if the alert goes away. */
  protected readonly menuCard = computed(() => {
    const menu = this.cardMenu();
    return menu === null ? null : this.cards().find((card) => card.alert.id === menu.alertId) ?? null;
  });
  private readonly cardMenuElement = viewChild<ElementRef<HTMLElement>>('cardMenu');
  protected readonly runningNow = signal(false);
  protected readonly notice = signal<DashboardNotice | null>(null);
  private noticeTimer: number | null = null;
  private readonly highlightTimers = new Set<number>();
  private requestedAlertId: number | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.highlightTimers.forEach((timer) => clearTimeout(timer));
      this.clearNoticeTimer();
    });
    this.live.changes$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((change) => this.animateChange(change));
    /* A notification click lands here with ?alert=<id>: open that tile as soon as it is available. */
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((parameters) => {
      const requested = Number(parameters.get('alert'));
      this.requestedAlertId = Number.isInteger(requested) && requested > 0 ? requested : null;
      this.openRequestedCard();
    });
    effect(() => {
      this.cards();
      this.openRequestedCard();
    });
    /* Focus the dialog once it is rendered so Escape closes it without an extra click. */
    effect(() => {
      if (this.selectedCard()) this.detailDialog()?.nativeElement.focus();
    });
    effect(() => storeViewSettings(this.settings()));
    /* The user's own "seen" marks come back on every (re)connection, like the tiles themselves. */
    effect(() => {
      if (this.live.connectionState() === 'connected') void this.acknowledgements.load();
    });
    /* Decks lay their cards out in pixels, so they track the grid's column width and their own width. */
    const resizeObserver = new ResizeObserver(() => this.measureDeck());
    effect(() => {
      resizeObserver.disconnect();
      const decks = this.decks();
      if (decks.length === 0) return;
      for (const deck of decks) resizeObserver.observe(deck.nativeElement);
      resizeObserver.observe(this.grid().nativeElement);
      this.measureDeck();
    });
    this.destroyRef.onDestroy(() => resizeObserver.disconnect());
    /* Once rendered, the menu is kept inside the viewport and takes the focus for keyboard use. */
    afterRenderEffect(() => {
      const menu = this.cardMenu();
      const element = this.cardMenuElement()?.nativeElement;
      if (!menu || !element) return;
      const { width, height } = element.getBoundingClientRect();
      const left = Math.min(menu.x, window.innerWidth - width - MENU_MARGIN_PIXELS);
      const top = Math.min(menu.y, window.innerHeight - height - MENU_MARGIN_PIXELS);
      element.style.left = `${Math.max(MENU_MARGIN_PIXELS, left)}px`;
      element.style.top = `${Math.max(MENU_MARGIN_PIXELS, top)}px`;
      if (!element.contains(document.activeElement)) this.menuItems()[0]?.focus();
    });
  }

  private openRequestedCard(): void {
    const alertId = this.requestedAlertId;
    if (alertId === null) return;
    const card = this.cards().find((candidate) => candidate.alert.id === alertId);
    if (!card) return;
    this.requestedAlertId = null;
    this.selectedCard.set(card);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { alert: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  /* Every deck spans the whole grid row, so measuring the first one sizes them all. */
  private measureDeck(): void {
    const deck = this.decks()[0]?.nativeElement;
    if (!deck) return;
    const firstColumn = getComputedStyle(this.grid().nativeElement).gridTemplateColumns.split(' ')[0];
    this.deckWidth.set(deck.clientWidth);
    this.deckCardWidth.set(Math.min(deck.clientWidth, Number.parseFloat(firstColumn) || deck.clientWidth));
  }

  protected openCard(card: DashboardAlertCard): void {
    this.selectedCard.set(card);
  }

  protected openCardFromKeyboard(event: Event, card: DashboardAlertCard): void {
    event.preventDefault();
    this.openCard(card);
  }

  protected closeCard(): void {
    this.selectedCard.set(null);
  }

  /* Secondary click, the context-menu key or Shift+F10; the keyboard ones report no pointer position. */
  protected openCardMenu(event: MouseEvent, card: DashboardAlertCard): void {
    const trigger = event.currentTarget;
    if (!(trigger instanceof HTMLElement)) return;
    event.preventDefault();
    let x = event.clientX;
    let y = event.clientY;
    if (x === 0 && y === 0) {
      const rect = trigger.getBoundingClientRect();
      x = rect.left + MENU_KEYBOARD_OFFSET_PIXELS;
      y = rect.top + MENU_KEYBOARD_OFFSET_PIXELS;
    }
    this.cardMenu.set({ alertId: card.alert.id, x, y });
  }

  protected closeCardMenu(restoreFocus = false): void {
    const menu = this.cardMenu();
    if (menu === null) return;
    this.cardMenu.set(null);
    /* Found again once rendered: muting moves the card to another block, which recreates its element. */
    if (restoreFocus) afterNextRender(() => this.focusCard(menu.alertId), { injector: this.injector });
  }

  private focusCard(alertId: number): void {
    this.host.querySelector<HTMLElement>(`.alert-card[data-alert-id="${alertId}"]`)?.focus();
  }

  /* A secondary click outside the menu only dismisses it, instead of opening the browser's own. */
  protected dismissCardMenu(event: Event): void {
    event.preventDefault();
    this.closeCardMenu();
  }

  protected moveMenuFocus(event: Event, offset: number): void {
    event.preventDefault();
    const items = this.menuItems();
    if (items.length === 0) return;
    const current = items.findIndex((item) => item === document.activeElement);
    items[(current + offset + items.length) % items.length].focus();
  }

  private menuItems(): HTMLElement[] {
    return Array.from(this.cardMenuElement()?.nativeElement.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []);
  }

  protected menuLabel(card: DashboardAlertCard): string {
    return this.localization.translate('dashboard.menu.label').replace('{name}', card.alert.name);
  }

  /* Silencing only makes sense while there is something to recover from. */
  protected canSilence(card: DashboardAlertCard): boolean {
    const state = this.cardState(card);
    return state === 'error' || state === 'warn';
  }

  protected ignoreCard(card: DashboardAlertCard): void {
    this.closeCardMenu(true);
    this.rearrange(() => this.mute.ignore(card.alert.id));
  }

  protected silenceCard(card: DashboardAlertCard): void {
    this.closeCardMenu(true);
    this.rearrange(() => this.mute.silence(card.alert.id));
  }

  protected unmuteCard(card: DashboardAlertCard): void {
    this.closeCardMenu(true);
    this.rearrange(() => this.mute.unmute(card.alert.id));
  }

  /* Administrators run any alert; a viewer with DASHBOARD_RUN only enabled ones. */
  protected canRunCard(card: DashboardAlertCard): boolean {
    return this.isAdmin || (this.canRunFromDashboard && card.alert.enabled);
  }

  protected async runCardNow(card: DashboardAlertCard): Promise<void> {
    this.closeCardMenu(true);
    // A disabled alert can still be run on demand by an administrator, so it is confirmed first.
    if (this.isAdmin && !card.alert.enabled && !window.confirm(this.localization.translate('alerts.runDisabledConfirm'))) return;
    if (this.runningNow()) return;
    this.runningNow.set(true);
    try {
      if (this.isAdmin) await this.alertApi.runAlertNow(card.alert.id);
      else await this.alertApi.runAlertFromDashboard(card.alert.id);
      // A viewer has no access to the execution history, so the result is announced on the card instead.
      const startedKey = this.isAdmin ? 'alerts.runStarted' : 'dashboard.run.started';
      this.showNotice(this.localization.translate(startedKey).replace('{name}', card.alert.name), false);
    } catch (error) {
      this.showNotice(this.runErrorMessage(error, card), true);
    } finally {
      this.runningNow.set(false);
    }
  }

  private runErrorMessage(error: unknown, card: DashboardAlertCard): string {
    const code = error instanceof ApiRequestError ? error.code : undefined;
    switch (code) {
      case 'ALERT_ALREADY_RUNNING': return this.localization.translate('alerts.runAlreadyRunning').replace('{name}', card.alert.name);
      case 'MAINTENANCE_MODE_ACTIVE': return this.localization.translate('alerts.runMaintenanceMode');
      case 'DASHBOARD_RUN_RATE_LIMIT': return this.localization.translate('dashboard.run.rateLimited');
      case 'DASHBOARD_RUN_LIMIT_UNAVAILABLE': return this.localization.translate('dashboard.run.unavailable');
      case 'ALERT_DISABLED': return this.localization.translate('dashboard.run.disabled').replace('{name}', card.alert.name);
      default: return error instanceof Error ? error.message : this.localization.translate('alerts.error');
    }
  }

  protected acknowledgeCard(card: DashboardAlertCard): void {
    this.closeCardMenu(true);
    this.rearrange(() => {
      this.acknowledgements.acknowledge(card.alert.id).catch(() =>
        this.showNotice(this.localization.translate('dashboard.pending.acknowledgeFailed').replace('{name}', card.alert.name), true));
    });
  }

  protected openCardHistory(card: DashboardAlertCard): void {
    this.closeCardMenu();
    void this.router.navigate(['/alerts'], { queryParams: { tab: 'history', alertId: card.alert.id } });
  }

  private showNotice(message: string, error: boolean): void {
    this.clearNoticeTimer();
    this.notice.set({ message, error });
    this.noticeTimer = window.setTimeout(() => {
      this.noticeTimer = null;
      this.notice.set(null);
    }, NOTICE_MILLIS);
  }

  private clearNoticeTimer(): void {
    if (this.noticeTimer !== null) clearTimeout(this.noticeTimer);
    this.noticeTimer = null;
  }

  protected updateLocale(locale: string): void {
    this.localization.setLocale(locale);
  }

  protected async logout(): Promise<void> {
    await this.sessionActions.logout();
  }

  protected openLabel(card: DashboardAlertCard): string {
    return this.localization.translate('dashboard.card.open').replace('{name}', card.alert.name);
  }

  protected triggerLabel(execution: AlertExecution): string {
    return execution.trigger ? this.localization.translate(TRIGGER_LABEL_KEYS[execution.trigger]) : '—';
  }

  protected workerLabel(execution: AlertExecution): string {
    if (execution.workerName === null) return '—';
    const address = execution.workerIpAddress ? ` (${execution.workerIpAddress}:${execution.workerPort ?? ''})` : '';
    return `${execution.workerName}${address}`;
  }

  protected fullMessage(execution: AlertExecution): string {
    if (execution.status === 'ERROR') {
      return [execution.errorType, execution.errorMessage].filter((part) => part !== null).join('\n');
    }
    return execution.statusMessage === null ? '' : JSON.stringify(execution.statusMessage, null, 2);
  }

  protected historyTitle(card: DashboardAlertCard): string {
    return this.localization.translate('dashboard.detail.history').replace('{days}', String(card.historyWindowDays));
  }

  protected noPreviousIssueLabel(card: DashboardAlertCard): string {
    return this.localization.translate('dashboard.detail.noPreviousIssue').replace('{days}', String(card.historyWindowDays));
  }

  /**
   * Reacts to a store change before the DOM caught up with it. A card whose
   * result status changed fades to its new colour and pulses in place first;
   * only then does it slide to where the board order puts it, with the other
   * cards making room (FLIP over the rects captured here). A repeated result
   * (same status again) or a new page just lets the others make room quietly.
   */
  private animateChange(change: DashboardChange): void {
    const before = this.cardRects();
    if (change.kind === 'update' && change.alertId !== null) {
      const selected = this.selectedCard();
      if (selected?.alert.id === change.alertId) {
        this.selectedCard.set(this.cards().find((card) => card.alert.id === change.alertId) ?? selected);
      }
      if (change.stateChanged) this.highlight(change.alertId);
    }
    const delay = change.stateChanged ? MOVE_DELAY_MILLIS : 0;
    afterNextRender(() => this.slideToNewPositions(before, delay), { injector: this.injector });
  }

  /* A card moved by the user slides to its new place the same way a live change does. */
  private rearrange(change: () => void): void {
    const before = this.cardRects();
    change();
    afterNextRender(() => this.slideToNewPositions(before, 0), { injector: this.injector });
  }

  private highlight(alertId: number): void {
    this.changedIds.update((ids) => new Set(ids).add(alertId));
    const timer = window.setTimeout(() => {
      this.highlightTimers.delete(timer);
      this.changedIds.update((ids) => {
        const next = new Set(ids);
        next.delete(alertId);
        return next;
      });
    }, CHANGE_HIGHLIGHT_MILLIS);
    this.highlightTimers.add(timer);
  }

  private cardElements(): readonly HTMLElement[] {
    return Array.from(this.host.querySelectorAll<HTMLElement>('.alert-card[data-alert-id]'));
  }

  private cardRects(): ReadonlyMap<string, DOMRect> {
    return new Map(this.cardElements().map((element) => [element.dataset['alertId'] ?? '', element.getBoundingClientRect()]));
  }

  private slideToNewPositions(before: ReadonlyMap<string, DOMRect>, delay: number): void {
    /* The global reduced-motion rule covers CSS animations, not Web Animations. */
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    for (const element of this.cardElements()) {
      const previous = before.get(element.dataset['alertId'] ?? '');
      if (!previous) continue;
      const current = element.getBoundingClientRect();
      const deltaX = previous.left - current.left;
      const deltaY = previous.top - current.top;
      if (deltaX === 0 && deltaY === 0) continue;
      element.animate(
        [{ transform: `translate(${deltaX}px, ${deltaY}px)` }, { transform: 'none' }],
        { duration: MOVE_DURATION_MILLIS, delay, fill: 'backwards', easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
      );
    }
  }

  protected enterDelay(card: DashboardAlertCard): string {
    return `${this.live.arrivalOffset(card.alert.id) * ENTER_STAGGER_MILLIS}ms`;
  }

  protected loadingLabel(): string {
    return this.localization.translate('dashboard.loading')
      .replace('{loaded}', String(this.live.loadedFromPages()))
      .replace('{total}', String(this.totalCards() ?? '…'));
  }

  protected dynamic(key: string): string {
    return this.localization.translateDynamic(key);
  }

  /** State of the last result alone, for places that name it rather than color the whole tile. */
  protected resultState(card: DashboardAlertCard): CardState {
    return card.lastExecution ? CARD_STATES[card.lastExecution.status] : 'none';
  }

  protected pendingDetail(card: DashboardAlertCard, pending: PendingIssue): string {
    const label = this.localization.translate(PENDING_LABEL_KEYS[pending.status]).replace('{elapsed}', this.formatElapsed(pending.at));
    return card.lastExecution?.status === 'SUCCESS' ? `${label} · ${this.localization.translate('dashboard.pending.nowSuccess')}` : label;
  }

  /* An issue the user has not seen yet raises the tile to that status, even after the alert recovered. */
  protected cardState(card: DashboardAlertCard): CardState {
    const status = effectiveStatus(card, this.pendingOf(card));
    return status ? CARD_STATES[status] : 'none';
  }

  protected cardClasses(card: DashboardAlertCard): string {
    const state = this.cardState(card);
    const { flatGreens, minified } = this.settings();
    let tone = '';
    if (state === 'success') tone = flatGreens ? ' alert-card--success-flat' : ` alert-card--success-${this.successAge(card)}`;
    return `alert-card alert-card--${state}`
      + tone
      + (minified ? ' alert-card--mini' : '')
      + (this.mute.isMuted(card.alert.id) ? ' alert-card--muted' : '')
      + (this.changedIds().has(card.alert.id) ? ' alert-card--changed' : '');
  }

  /* Green tiles darken the longer the alert has stayed green. */
  protected successAge(card: DashboardAlertCard): SuccessAge {
    const now = Date.now();
    const since = stateChangedAt(card);
    if (now - since <= HOUR_MILLIS) return 'very-fresh';
    const days = calendarDaysBetween(since, now);
    if (days === 0) return 'fresh';
    if (days <= 2) return 'recent';
    if (days <= 4) return 'aging';
    if (days <= 7) return 'old';
    return 'very-old';
  }

  protected historyState(status: AlertExecutionStatus): CardState {
    return CARD_STATES[status];
  }

  protected statusKey(status: AlertExecutionStatus): TranslationKey {
    return STATUS_LABEL_KEYS[status];
  }

  protected statusLabel(card: DashboardAlertCard): string {
    const key = card.lastExecution ? STATUS_LABEL_KEYS[card.lastExecution.status] : 'dashboard.status.none';
    return this.localization.translate(key);
  }

  protected historyWindowTitle(card: DashboardAlertCard): string {
    return this.localization.translate('dashboard.card.historyWindow').replace('{days}', String(card.historyWindowDays));
  }

  /**
   * Summarizes the look-back window in one pill: a worse status the alert
   * recovered from, an ongoing non-success streak, or a steady success.
   */
  protected stability(card: DashboardAlertCard): Stability | null {
    const execution = card.lastExecution;
    /* An unseen issue as bad as the current result takes the pill until the user marks it as seen. */
    const pending = this.pendingOf(card);
    if (pending !== null && (execution === null || SEVERITY[pending.status] >= SEVERITY[execution.status])) {
      /* The pill's color already tells WARN from ERROR and the badge names the current result, so the tile keeps it short. */
      return {
        state: STABILITY_STATES[pending.status],
        label: this.localization.translate('dashboard.pending.short').replace('{elapsed}', this.formatElapsed(pending.at)),
        title: `${this.pendingDetail(card, pending)}. ${this.localization.translate('dashboard.pending.hint')}`,
      };
    }

    const history = card.history;
    if (execution === null || history === null) return null;

    const worst = history.worstStatus;
    if (worst !== 'SUCCESS' && SEVERITY[worst] > SEVERITY[execution.status]) {
      return {
        state: STABILITY_STATES[worst],
        label: this.localization.translate(INCIDENT_LABEL_KEYS[worst])
          .replace('{elapsed}', this.formatElapsed(history.worstStatusLastAt)),
      };
    }
    if (execution.status === 'SUCCESS') {
      return {
        state: 'stable',
        label: this.localization.translate('dashboard.card.stable').replace('{days}', String(card.historyWindowDays)),
      };
    }
    return {
      state: STABILITY_STATES[execution.status],
      label: this.localization.translate(ONGOING_LABEL_KEYS[execution.status])
        .replace('{elapsed}', this.formatElapsed(history.currentStatusSince)),
    };
  }

  protected lastExecutionMessage(card: DashboardAlertCard): string {
    const execution = card.lastExecution;
    return execution === null ? '' : this.alertMessages.tileMessage(card.alert.templateKey, execution);
  }

  /** The template formatter's readable summary of an execution, when it has one. */
  protected executionSummary(card: DashboardAlertCard, execution: AlertExecution): string | null {
    return this.alertMessages.summary(card.alert.templateKey, execution);
  }

  protected formatDuration(milliseconds: number): string {
    if (milliseconds < 1_000) return `${Math.max(0, Math.round(milliseconds))} ms`;
    const totalSeconds = Math.floor(milliseconds / 1_000);
    const hours = Math.floor(totalSeconds / 3_600);
    const minutes = Math.floor((totalSeconds % 3_600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) return `${hours} h ${minutes} min ${seconds} s`;
    if (minutes > 0) return `${minutes} min ${seconds} s`;
    return `${seconds} s`;
  }

  protected runningLabel(runningSince: string): string {
    return this.localization.translate('dashboard.card.runningSince').replace('{elapsed}', this.formatElapsed(runningSince));
  }

  /* Compact elapsed time for pills: "45 s", "12 min", "5 h", "3 d". */
  protected formatElapsed(timestamp: string): string {
    const elapsedSeconds = Math.floor(Math.max(0, Date.now() - Date.parse(timestamp)) / 1_000);
    if (elapsedSeconds < 60) return `${elapsedSeconds} s`;
    const elapsedMinutes = Math.floor(elapsedSeconds / 60);
    if (elapsedMinutes < 60) return `${elapsedMinutes} min`;
    if (elapsedMinutes < 1_440) return `${Math.floor(elapsedMinutes / 60)} h`;
    return `${Math.floor(elapsedMinutes / 1_440)} d`;
  }

  protected relativeTime(timestamp: string): string {
    const elapsedSeconds = Math.floor(Math.max(0, Date.now() - Date.parse(timestamp)) / 1_000);
    let value: number;
    let unit: Intl.RelativeTimeFormatUnit;
    if (elapsedSeconds < 60) {
      value = elapsedSeconds;
      unit = 'second';
    } else if (elapsedSeconds < 3_600) {
      value = Math.floor(elapsedSeconds / 60);
      unit = 'minute';
    } else if (elapsedSeconds < 86_400) {
      value = Math.floor(elapsedSeconds / 3_600);
      unit = 'hour';
    } else {
      value = Math.floor(elapsedSeconds / 86_400);
      unit = 'day';
    }

    return new Intl.RelativeTimeFormat(this.localization.locale(), { numeric: 'always' })
      .format(-value, unit);
  }
}
