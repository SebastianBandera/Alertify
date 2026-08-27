import { DOCUMENT } from '@angular/common';
import { effect, inject, Injectable, signal } from '@angular/core';

import {
  AppLocale,
  TranslationDictionary,
  TranslationKey,
} from './localization.types';
import { EN_LOCALE_LABEL, EN_TRANSLATIONS } from './translations/en.translations';
import {
  ES_UY_LOCALE_LABEL,
  ES_UY_TRANSLATIONS,
} from './translations/es-uy.translations';

const DEFAULT_LOCALE: AppLocale = 'en';
const STORAGE_KEY = 'alertify.locale';

const TRANSLATIONS: Readonly<Record<AppLocale, TranslationDictionary>> = {
  en: EN_TRANSLATIONS,
  'es-UY': ES_UY_TRANSLATIONS,
};

function isAppLocale(value: string | null): value is AppLocale {
  return value === 'en' || value === 'es-UY';
}

function readStoredLocale(): AppLocale {
  try {
    const storedLocale = localStorage.getItem(STORAGE_KEY);
    return isAppLocale(storedLocale) ? storedLocale : DEFAULT_LOCALE;
  } catch {
    return DEFAULT_LOCALE;
  }
}

@Injectable({ providedIn: 'root' })
export class LocalizationService {
  private readonly document = inject(DOCUMENT);

  readonly locale = signal<AppLocale>(readStoredLocale());
  readonly localeOptions: readonly Readonly<{ value: AppLocale; label: string }>[] = [
    { value: 'en', label: EN_LOCALE_LABEL },
    { value: 'es-UY', label: ES_UY_LOCALE_LABEL },
  ];

  constructor() {
    effect(() => {
      this.document.documentElement.lang = this.locale();
    });
  }

  translate(key: TranslationKey): string {
    return TRANSLATIONS[this.locale()][key];
  }

  translateDynamic(key: string): string {
    const dictionary = TRANSLATIONS[this.locale()] as Readonly<Record<string, string>>;
    return dictionary[key] ?? key;
  }

  setLocale(locale: string): void {
    if (!isAppLocale(locale)) {
      return;
    }

    this.locale.set(locale);

    try {
      localStorage.setItem(STORAGE_KEY, locale);
    } catch {
      // The selection still applies to this page when browser storage is unavailable.
    }
  }
}
