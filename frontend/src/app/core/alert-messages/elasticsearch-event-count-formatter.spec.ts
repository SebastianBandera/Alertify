import { describe, expect, it } from 'vitest';

import { AlertMessageContext, AlertMessagePresentation } from './alert-message-formatter';
import { ALERT_MESSAGE_FORMATTERS } from './alert-message-formatters';
import { ALERT_CHART_EXTRACTORS } from '../alert-charts/alert-chart-extractors';
import { AlertChartContext } from '../alert-charts/alert-chart-definition';
import { AppLocale } from '../i18n/localization.types';
import { EN_TRANSLATIONS } from '../i18n/translations/en.translations';
import { ES_UY_TRANSLATIONS } from '../i18n/translations/es-uy.translations';

const template = 'app.alertify.alerts.templates.ElasticsearchEventCountAlertTemplate';

function present(message: Readonly<Record<string, unknown>>, locale: AppLocale = 'es-UY'): AlertMessagePresentation {
  const dictionary: Readonly<Record<string, string>> = locale === 'en' ? EN_TRANSLATIONS : ES_UY_TRANSLATIONS;
  const context: AlertMessageContext = {
    message, status: 'WARN', locale,
    translate: (key, params = {}) => dictionary[key]?.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? '')) ?? null,
    formatNumber: (value) => String(value),
    formatDate: (value) => String(value),
    formatDuration: (value) => String(value),
  };
  return ALERT_MESSAGE_FORMATTERS[template](context) as AlertMessagePresentation;
}

describe('Elasticsearch event count presentation', () => {
  const message = { count: 0, thresholdType: 'warn_if_equal', threshold: 0, windowFrom: '2026-11-01T11:54:00Z', windowTo: '2026-11-01T11:59:00Z' };

  it('shows zero counts, threshold and window in both languages', () => {
    const es = present(message).summary;
    expect(es).toContain('0 eventos');
    expect(es).toContain('alerta si = 0');
    expect(es).toContain(message.windowFrom);
    expect(es).toContain(message.windowTo);
    expect(present(message, 'en').summary).toContain('0 events');
  });

  it('shows incomplete queries as failures without inventing zero events', () => {
    expect(present({ failureReason: 'elasticsearch_partial_result' }).summary).toBe('Elasticsearch devolvió un conteo incompleto');
    expect(present({ failureReason: 'elasticsearch_invalid_response' }, 'en').summary).toBe('Elasticsearch returned an invalid count response');
  });

  it('keeps the incident identity stable as the evaluation window and count change', () => {
    const first = present(message);
    const next = present({ ...message, count: 3, windowFrom: '2026-11-01T11:55:00Z' });
    expect(first.summary).not.toBe(next.summary);
    expect(first.identity).toBe(next.identity);
  });

  it('provides translations for all template and result keys', () => {
    for (const [key, value] of Object.entries(EN_TRANSLATIONS)) {
      if (key.includes('elasticsearch') || key === 'chart.events') {
        expect(value).not.toBe('');
        expect((ES_UY_TRANSLATIONS as Readonly<Record<string, string>>)[key]).toBeTruthy();
      }
    }
  });

  it('charts zero counts and excludes failed query results', () => {
    const extract = (result: Readonly<Record<string, unknown>>) => ALERT_CHART_EXTRACTORS[template].extract({ result } as AlertChartContext);
    expect(extract({ count: 0 })).toBe(0);
    expect(extract({ count: 21 })).toBe(21);
    expect(extract({ failureReason: 'elasticsearch_partial_result' })).toBeNull();
  });
});
