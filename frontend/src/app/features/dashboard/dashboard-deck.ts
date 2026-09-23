import { computed, Signal, signal } from '@angular/core';

import { DashboardAlertCard } from './dashboard-card';

/* Gap between deck cards when the row is wide enough for them not to overlap. */
const DECK_GAP_PIXELS = 14;

/**
 * One row of overlapping cards, like a hand of playing cards. Every deck spans
 * the full grid row, so all of them share the row and card widths the board
 * measures; each keeps its own cards and the one brought to the front.
 */
export class DashboardDeck {
  readonly activeIndex = signal<number | null>(null);
  readonly step = computed(() => {
    const count = this.cards().length;
    if (count <= 1) return 0;
    const cardWidth = this.cardWidth();
    return Math.max(0, Math.min(cardWidth + DECK_GAP_PIXELS, (this.width() - cardWidth) / (count - 1)));
  });

  constructor(
    readonly cards: Signal<readonly DashboardAlertCard[]>,
    private readonly width: Signal<number>,
    readonly cardWidth: Signal<number>,
  ) {}

  slotLeft(index: number): number {
    return index * this.step();
  }

  slotZ(index: number): number {
    return this.activeIndex() === index ? this.cards().length + 1 : index + 1;
  }

  /* Whichever card sits under the pointer comes to the front, like fanning a hand of cards. */
  scrub(event: PointerEvent): void {
    const deck = event.currentTarget;
    const step = this.step();
    if (!(deck instanceof HTMLElement) || step === 0) return;
    const x = event.clientX - deck.getBoundingClientRect().left;
    this.activeIndex.set(Math.max(0, Math.min(this.cards().length - 1, Math.floor(x / step))));
  }

  leave(): void {
    this.activeIndex.set(null);
  }

  focus(index: number): void {
    this.activeIndex.set(index);
  }
}
