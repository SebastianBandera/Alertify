import { computed, Signal, signal } from '@angular/core';

import { DashboardAlertCard } from './dashboard-card';

/* Gap between deck cards when the row is wide enough for them not to overlap. */
const DECK_GAP_PIXELS = 14;
/* Minimum visible strip between overlapping cards before another row is dealt. */
const MIN_DECK_STEP_PIXELS = 36;

interface DashboardDeckRow {
  readonly cards: readonly DashboardAlertCard[];
  readonly startIndex: number;
  readonly step: number;
}

/**
 * One or more rows of overlapping cards, like hands of playing cards. Every
 * row spans the grid, and another row is dealt whenever fitting more cards
 * would leave less than MIN_DECK_STEP_PIXELS visible between them.
 */
export class DashboardDeck {
  readonly activeIndex = signal<number | null>(null);
  readonly rows = computed<readonly DashboardDeckRow[]>(() => {
    const cards = this.cards();
    if (cards.length === 0) return [];

    const width = this.width();
    const cardWidth = this.cardWidth();
    /* Keep one measurable row in the DOM until ResizeObserver supplies dimensions. */
    if (width <= 0 || cardWidth <= 0) return [{ cards, startIndex: 0, step: 0 }];

    const availableWidth = Math.max(0, width - cardWidth);
    const cardsPerRow = Math.max(1, Math.floor(availableWidth / MIN_DECK_STEP_PIXELS) + 1);
    const rows: DashboardDeckRow[] = [];
    for (let startIndex = 0; startIndex < cards.length; startIndex += cardsPerRow) {
      const rowCards = cards.slice(startIndex, startIndex + cardsPerRow);
      const step = rowCards.length <= 1
        ? 0
        : Math.min(cardWidth + DECK_GAP_PIXELS, availableWidth / (rowCards.length - 1));
      rows.push({ cards: rowCards, startIndex, step });
    }

    return rows;
  });

  constructor(
    readonly cards: Signal<readonly DashboardAlertCard[]>,
    private readonly width: Signal<number>,
    readonly cardWidth: Signal<number>,
  ) {}

  slotLeft(index: number, row: DashboardDeckRow): number {
    return index * row.step;
  }

  slotZ(index: number): number {
    return this.activeIndex() === index ? this.cards().length + 1 : index + 1;
  }

  /* Whichever card sits under the pointer comes to the front, like fanning a hand of cards. */
  scrub(event: PointerEvent, row: DashboardDeckRow): void {
    const deck = event.currentTarget;
    if (!(deck instanceof HTMLElement) || row.step === 0) return;
    const x = event.clientX - deck.getBoundingClientRect().left;
    const rowIndex = Math.max(0, Math.min(row.cards.length - 1, Math.floor(x / row.step)));
    this.activeIndex.set(row.startIndex + rowIndex);
  }

  leave(): void {
    this.activeIndex.set(null);
  }

  focus(index: number): void {
    this.activeIndex.set(index);
  }
}
