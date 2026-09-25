import { inject, Injectable } from '@angular/core';

import { AlertExecution } from '../api/alert-api.service';
import { LocalizationService } from '../i18n/localization.service';
import { AlertMessageContext } from './alert-message-formatter';
import { ALERT_MESSAGE_FORMATTERS } from './alert-message-formatters';

const PLACEHOLDER = /\{(\w+)\}/g;

/**
 * Readable summaries of execution results, produced by the formatter
 * registered for the alert's template. Reading the language signal here
 * makes callers re-render their summaries when the user switches language.
 */
@Injectable({ providedIn: 'root' })
export class AlertMessageService {
  private readonly localization = inject(LocalizationService);

  /** Text for a dashboard tile: the formatter's summary when there is one, otherwise the raw result. */
  tileMessage(templateKey: string, execution: AlertExecution): string {
    if (execution.status === 'ERROR') return execution.errorMessage ?? execution.errorType ?? '';
    if (execution.statusMessage === null) return '';
    return this.summary(templateKey, execution) ?? JSON.stringify(execution.statusMessage);
  }

  /**
   * The formatter's summary of a SUCCESS or WARN result, or null when the
   * template has no formatter, the result is not an object, or the formatter
   * returns nothing or fails: a broken formatter must never break the board.
   */
  summary(templateKey: string, execution: AlertExecution): string | null {
    const formatter = ALERT_MESSAGE_FORMATTERS[templateKey];
    const message = execution.statusMessage;
    if (!formatter || execution.status === 'ERROR' || message === null || typeof message !== 'object' || Array.isArray(message)) return null;

    try {
      const summary = formatter(this.context(message as Readonly<Record<string, unknown>>, execution.status));
      return typeof summary === 'string' && summary.trim() !== '' ? summary.trim() : null;
    } catch {
      return null;
    }
  }

  private context(message: Readonly<Record<string, unknown>>, status: 'SUCCESS' | 'WARN'): AlertMessageContext {
    const locale = this.localization.locale();
    const numbers = new Intl.NumberFormat(locale);
    const seconds = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 });
    const dates = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' });
    return {
      message,
      status,
      locale,
      translate: (key, params) => {
        const template = this.localization.translateDynamic(key);
        if (template === key) return null;
        return template.replace(PLACEHOLDER, (placeholder, name: string) => {
          if (!params || !(name in params)) return placeholder;
          const value = params[name];
          return value === null || value === undefined || value === '' ? '—' : String(value);
        });
      },
      formatNumber: (value) => typeof value === 'number' && Number.isFinite(value) ? numbers.format(value) : String(value ?? '—'),
      formatDuration: (milliseconds) => {
        if (typeof milliseconds !== 'number' || !Number.isFinite(milliseconds)) return '—';
        return milliseconds < 1_000 ? `${numbers.format(Math.round(milliseconds))} ms` : `${seconds.format(milliseconds / 1_000)} s`;
      },
      formatDate: (value) => {
        const date = typeof value === 'string' || typeof value === 'number' ? new Date(value) : null;
        return date === null || Number.isNaN(date.getTime()) ? String(value ?? '—') : dates.format(date);
      },
    };
  }
}
