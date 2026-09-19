import { DatePipe, NgTemplateOutlet } from '@angular/common';
import {
  afterNextRender,
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
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';

import { AlertExecution, AlertExecutionStatus, AlertTag } from '../../core/api/alert-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { TranslationKey } from '../../core/i18n/localization.types';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { DragScrollDirective } from '../../shared/drag-scroll/drag-scroll.directive';
import {
  compareDashboardCards,
  DASHBOARD_HISTORY_WINDOW_DAYS,
  DashboardAlertCard,
  stateChangedAt,
} from './dashboard-card';
import { DashboardChange, DashboardLiveService } from './dashboard-live.service';
import { CardState, readStoredViewSettings, storeViewSettings } from './dashboard-view-settings';
import { DashboardRibbonComponent } from './ribbon/dashboard-ribbon.component';

type StabilityState = 'stable' | 'warn' | 'error';
/* How long the alert has been green, from a state change within the hour to a week or more. */
type SuccessAge = 'very-fresh' | 'fresh' | 'recent' | 'aging' | 'old' | 'very-old';

interface Stability {
  readonly state: StabilityState;
  readonly label: string;
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
/* Gap between deck cards when the row is wide enough for them not to overlap. */
const DECK_GAP_PIXELS = 14;

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
  imports: [DatePipe, NgTemplateOutlet, DragScrollDirective, DashboardRibbonComponent],
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
  private readonly channel = inject(AdminEventChannelService);
  protected readonly isAdmin = inject(AuthService).isAdmin;
  protected readonly cards = this.live.cards;
  /* Cards whose state just changed, while their highlight animation plays. */
  protected readonly changedIds = signal<ReadonlySet<number>>(new Set());
  protected readonly sortedCards = computed(() => [...this.cards()].sort(compareDashboardCards));
  protected readonly settings = signal(readStoredViewSettings());
  protected readonly availableTags = computed<readonly AlertTag[]>(() => {
    const tags = new Map<number, AlertTag>();
    for (const card of this.cards()) for (const tag of card.alert.tags) tags.set(tag.id, tag);
    return [...tags.values()].sort((left, right) => left.name.localeCompare(right.name));
  });
  protected readonly visibleCards = computed(() => {
    const { hiddenStates, hiddenTagIds } = this.settings();
    const hiddenTags = new Set(hiddenTagIds);
    return this.sortedCards().filter((card) =>
      !hiddenStates.includes(this.cardState(card))
        && (card.alert.tags.length === 0 || card.alert.tags.some((tag) => !hiddenTags.has(tag.id))));
  });
  /* Greens are dealt as a deck unless ungrouped: worse states first, the deck row, then never-executed. */
  protected readonly deckCards = computed(() =>
    this.settings().ungroupGreens ? [] : this.visibleCards().filter((card) => this.cardState(card) === 'success'));
  protected readonly leadingCards = computed(() =>
    this.deckCards().length === 0
      ? this.visibleCards()
      : this.visibleCards().filter((card) => this.cardState(card) === 'error' || this.cardState(card) === 'warn'));
  protected readonly trailingCards = computed(() =>
    this.deckCards().length === 0 ? [] : this.visibleCards().filter((card) => this.cardState(card) === 'none'));
  protected readonly deckActiveIndex = signal<number | null>(null);
  private readonly deckWidth = signal(0);
  private readonly deckCardWidth = signal(0);
  protected readonly deckStep = computed(() => {
    const count = this.deckCards().length;
    if (count <= 1) return 0;
    const cardWidth = this.deckCardWidth();
    return Math.max(0, Math.min(cardWidth + DECK_GAP_PIXELS, (this.deckWidth() - cardWidth) / (count - 1)));
  });
  private readonly deck = viewChild<ElementRef<HTMLElement>>('deck');
  private readonly grid = viewChild.required<ElementRef<HTMLElement>>('grid');
  protected readonly totalCards = this.live.totalCards;
  protected readonly loading = this.live.loading;
  /* Placeholders for the tiles still to come: the rest of the snapshot, or the whole board while the channel connects. */
  protected readonly skeletons = computed(() => {
    const total = this.totalCards();
    let pending = 0;
    if (this.loading()) pending = total === null ? INITIAL_SKELETONS : total - this.live.loadedFromPages();
    else if (this.isAdmin && this.cards().length === 0 && this.channel.connectionState() !== 'connected') pending = INITIAL_SKELETONS;
    return Array.from({ length: Math.max(0, pending) }, (_, index) => index);
  });
  protected readonly historyWindowDays = DASHBOARD_HISTORY_WINDOW_DAYS;
  protected readonly selectedCard = signal<DashboardAlertCard | null>(null);
  private readonly detailDialog = viewChild<ElementRef<HTMLElement>>('detailDialog');
  private readonly highlightTimers = new Set<number>();
  private requestedAlertId: number | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.highlightTimers.forEach((timer) => clearTimeout(timer)));
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
    /* The deck lays its cards out in pixels, so it tracks the grid's column width and its own width. */
    const resizeObserver = new ResizeObserver(() => this.measureDeck());
    effect(() => {
      resizeObserver.disconnect();
      const deck = this.deck()?.nativeElement;
      if (!deck) return;
      resizeObserver.observe(deck);
      resizeObserver.observe(this.grid().nativeElement);
      this.measureDeck();
    });
    this.destroyRef.onDestroy(() => resizeObserver.disconnect());
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

  private measureDeck(): void {
    const deck = this.deck()?.nativeElement;
    if (!deck) return;
    const firstColumn = getComputedStyle(this.grid().nativeElement).gridTemplateColumns.split(' ')[0];
    this.deckWidth.set(deck.clientWidth);
    this.deckCardWidth.set(Math.min(deck.clientWidth, Number.parseFloat(firstColumn) || deck.clientWidth));
  }

  protected deckCardWidthPx(): number {
    return this.deckCardWidth();
  }

  protected deckSlotLeft(index: number): number {
    return index * this.deckStep();
  }

  protected deckSlotZ(index: number): number {
    return this.deckActiveIndex() === index ? this.deckCards().length + 1 : index + 1;
  }

  /* Whichever card sits under the pointer comes to the front, like fanning a hand of cards. */
  protected scrubDeck(event: PointerEvent): void {
    const deck = this.deck()?.nativeElement;
    const step = this.deckStep();
    if (!deck || step === 0) return;
    const x = event.clientX - deck.getBoundingClientRect().left;
    this.deckActiveIndex.set(Math.max(0, Math.min(this.deckCards().length - 1, Math.floor(x / step))));
  }

  protected leaveDeck(): void {
    this.deckActiveIndex.set(null);
  }

  protected focusDeckCard(index: number): void {
    this.deckActiveIndex.set(index);
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

  protected historyTitle(): string {
    return this.localization.translate('dashboard.detail.history').replace('{days}', String(this.historyWindowDays));
  }

  /**
   * Reacts to a store change before the DOM caught up with it. An updated card
   * fades to its new colour and pulses in place first; only then does it slide
   * to where the board order puts it, with the other cards making room (FLIP
   * over the rects captured here). New pages just let the others make room.
   */
  private animateChange(change: DashboardChange): void {
    const before = this.cardRects();
    if (change.kind === 'update' && change.alertId !== null) {
      const selected = this.selectedCard();
      if (selected?.alert.id === change.alertId) {
        this.selectedCard.set(this.cards().find((card) => card.alert.id === change.alertId) ?? selected);
      }
      this.highlight(change.alertId);
    }
    const delay = change.kind === 'update' ? MOVE_DELAY_MILLIS : 0;
    afterNextRender(() => this.slideToNewPositions(before, delay), { injector: this.injector });
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

  protected cardState(card: DashboardAlertCard): CardState {
    return card.lastExecution ? CARD_STATES[card.lastExecution.status] : 'none';
  }

  protected cardClasses(card: DashboardAlertCard): string {
    const state = this.cardState(card);
    const { flatGreens, minified } = this.settings();
    let tone = '';
    if (state === 'success') tone = flatGreens ? ' alert-card--success-flat' : ` alert-card--success-${this.successAge(card)}`;
    return `alert-card alert-card--${state}`
      + tone
      + (minified ? ' alert-card--mini' : '')
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

  protected historyWindowTitle(): string {
    return this.localization.translate('dashboard.card.historyWindow').replace('{days}', String(this.historyWindowDays));
  }

  /**
   * Summarizes the look-back window in one pill: a worse status the alert
   * recovered from, an ongoing non-success streak, or a steady success.
   */
  protected stability(card: DashboardAlertCard): Stability | null {
    const execution = card.lastExecution;
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
        label: this.localization.translate('dashboard.card.stable').replace('{days}', String(this.historyWindowDays)),
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
    if (execution === null) return '';
    if (execution.status === 'ERROR') return execution.errorMessage ?? execution.errorType ?? '';
    if (execution.statusMessage === null) return '';
    return JSON.stringify(execution.statusMessage);
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
