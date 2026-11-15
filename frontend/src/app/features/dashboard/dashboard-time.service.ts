import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';

import { LocalizationService } from '../../core/i18n/localization.service';

/** One clock and formatter for the dashboard and its Pipe cards. Scoped to the dashboard view. */
@Injectable()
export class DashboardTimeService {
  private readonly localization = inject(LocalizationService);
  private readonly now = signal(Date.now());
  private readonly formatter = computed(() => new Intl.RelativeTimeFormat(this.localization.locale(), { numeric: 'always' }));

  constructor() {
    const timer = window.setInterval(() => this.now.set(Date.now()), 1_000);
    inject(DestroyRef).onDestroy(() => window.clearInterval(timer));
  }

  relativeTime(timestamp: string): string {
    const elapsedSeconds = Math.floor(Math.max(0, this.now() - Date.parse(timestamp)) / 1_000);
    if (!Number.isFinite(elapsedSeconds)) return '—';

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

    return this.formatter().format(-value, unit);
  }
}
