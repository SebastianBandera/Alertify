import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';

import { LocalizationService } from '../../../core/i18n/localization.service';
import { DashboardLiveService } from '../dashboard-live.service';

/* A reconnect usually lands well within this, so the initial connection and brief blips never flash the notice. */
const DISCONNECTED_GRACE_MILLIS = 2_000;
const IDLE_MILLIS = 10_000;
const ACTIVITY_EVENTS = ['pointermove', 'pointerdown', 'wheel', 'scroll', 'keydown', 'touchstart'] as const;

/**
 * Blurs the board behind a large notice while the live channel is down. A
 * click (or Escape) sets it aside so the last loaded tiles stay readable, and
 * it returns once the user stops interacting for a while.
 */
@Component({
  selector: 'app-dashboard-connection-overlay',
  templateUrl: './dashboard-connection-overlay.component.html',
  styleUrl: './dashboard-connection-overlay.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown.escape)': 'reveal()',
  },
})
export class DashboardConnectionOverlayComponent {
  protected readonly localization = inject(LocalizationService);
  private readonly live = inject(DashboardLiveService);
  private readonly document = inject(DOCUMENT);
  /* Retries alternate between both states, so they collapse into one flag that stays put meanwhile. */
  private readonly channelDown = computed(() => {
    const state = this.live.connectionState();
    return state === 'connecting' || state === 'reconnecting';
  });
  private readonly disconnected = signal(false);
  private readonly revealed = signal(false);
  protected readonly visible = computed(() => this.disconnected() && !this.revealed());
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly onActivity = (): void => this.restartIdleTimer();

  constructor() {
    effect((onCleanup) => {
      if (!this.channelDown()) {
        this.disconnected.set(false);
        this.setAside(false);
        return;
      }
      const timer = setTimeout(() => this.disconnected.set(true), DISCONNECTED_GRACE_MILLIS);
      onCleanup(() => clearTimeout(timer));
    });
    inject(DestroyRef).onDestroy(() => this.setAside(false));
  }

  protected reveal(): void {
    if (this.visible()) this.setAside(true);
  }

  /* While set aside, any sign of activity keeps the notice away; idling long enough brings it back. */
  private setAside(revealed: boolean): void {
    this.revealed.set(revealed);
    for (const name of ACTIVITY_EVENTS)
      this.document.removeEventListener(name, this.onActivity, { capture: true });
    this.clearIdleTimer();
    if (!revealed) return;

    for (const name of ACTIVITY_EVENTS)
      this.document.addEventListener(name, this.onActivity, { capture: true, passive: true });
    this.restartIdleTimer();
  }

  private restartIdleTimer(): void {
    this.clearIdleTimer();
    this.idleTimer = setTimeout(() => this.setAside(false), IDLE_MILLIS);
  }

  private clearIdleTimer(): void {
    if (this.idleTimer !== null) clearTimeout(this.idleTimer);
    this.idleTimer = null;
  }
}
