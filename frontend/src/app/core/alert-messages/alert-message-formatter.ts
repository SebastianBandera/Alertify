import { AppLocale } from '../i18n/localization.types';

/**
 * What a formatter receives to turn one execution's status message into a
 * short readable text. Everything is already bound to the user's language.
 */
export interface AlertMessageContext {
  /** The execution's status message, exactly as the template produced it. */
  readonly message: Readonly<Record<string, unknown>>;
  readonly status: 'SUCCESS' | 'WARN';
  /** Language the user selected, for any wording or formatting of your own. */
  readonly locale: AppLocale;
  /**
   * Looks the key up in the core and extended dictionaries of the current
   * language and replaces each {name} with the matching parameter; null when
   * the key is not defined.
   */
  translate(key: string, params?: Readonly<Record<string, unknown>>): string | null;
  /** Number formatted for the current language; the raw value when it is not a finite number. */
  formatNumber(value: unknown): string;
  /** Milliseconds as "850 ms" or "1.2 s" in the current language. */
  formatDuration(milliseconds: unknown): string;
  /** Date formatted for the current language; the raw value when it is not a parseable date. */
  formatDate(value: unknown): string;
}

/**
 * Turns a status message into the text shown on a dashboard tile. Returning
 * null, an empty text or throwing falls back to the raw JSON.
 */
export type AlertMessageFormatter = (context: AlertMessageContext) => string | null | undefined;

/** Formatters keyed by the template's fully qualified class name. */
export type AlertMessageFormatters = Readonly<Record<string, AlertMessageFormatter>>;
