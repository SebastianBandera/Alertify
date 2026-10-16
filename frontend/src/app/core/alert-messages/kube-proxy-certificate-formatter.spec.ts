import { describe, expect, it } from 'vitest';

import { AlertMessageContext, AlertMessagePresentation } from './alert-message-formatter';
import { ALERT_MESSAGE_FORMATTERS } from './alert-message-formatters';
import { AppLocale } from '../i18n/localization.types';
import { EN_TRANSLATIONS } from '../i18n/translations/en.translations';
import { ES_UY_TRANSLATIONS } from '../i18n/translations/es-uy.translations';

const formatter = ALERT_MESSAGE_FORMATTERS['app.alertify.alerts.templates.KubernetesKubeProxyCertificateExpiryAlertTemplate'];

function present(message: Readonly<Record<string, unknown>>, locale: AppLocale = 'es-UY'): AlertMessagePresentation {
  const dictionary: Readonly<Record<string, string>> = locale === 'en' ? EN_TRANSLATIONS : ES_UY_TRANSLATIONS;
  const context: AlertMessageContext = {
    message, status: 'WARN', locale,
    translate: (key, params = {}) => dictionary[key]?.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? '')) ?? null,
    formatNumber: (value) => String(value),
    formatDate: (value) => String(value),
    formatDuration: (value) => String(value),
  };
  return formatter(context) as AlertMessagePresentation;
}

describe('kube-proxy certificate presentation', () => {
  it('shows affected nodes and expiration without listing healthy nodes', () => {
    const result = present({ totalNodes: 3, warningNodes: 1, nodes: [
      { node: 'node-1', status: 'SUCCESS' },
      { node: 'node-2', status: 'WARN', reason: 'kubeProxyCertificateExpiring', notAfter: '2026-11-01', daysRemaining: 17 },
      { node: 'node-3', status: 'SUCCESS' },
    ] });
    expect(result.summary).toContain('1 de 3 nodos con aviso');
    expect(result.summary).toContain('node-2: Vence en 17 días (2026-11-01)');
    expect(result.summary).not.toContain('node-1');
  });

  it('translates operational failures and expired or future validity in both languages', () => {
    const nodes = [
      { node: 'node-1', status: 'WARN', reason: 'kubernetesRbacDenied' },
      { node: 'node-2', status: 'WARN', reason: 'kubeProxyCertificateExpired', notAfter: '2026-10-04' },
      { node: 'node-3', status: 'WARN', reason: 'kubeProxyCertificateNotYetValid', notBefore: '2027-01-01', notAfter: '2028-01-01' },
    ];
    const es = present({ totalNodes: 3, warningNodes: 3, nodes });
    expect(es.summary).toContain('el RBAC de Kubernetes denegó el acceso');
    expect(es.summary).toContain('Venció el 2026-10-04');
    expect(es.summary).toContain('No es válido hasta el 2027-01-01');
    const en = present({ totalNodes: 3, warningNodes: 3, nodes }, 'en');
    expect(en.summary).toContain('3 of 3 nodes with warnings');
    expect(en.summary).toContain('Expired on 2026-10-04');
    expect(en.summary).toContain('Not valid until 2027-01-01');
  });

  it('keeps the incident identity stable as the remaining days and query dates change', () => {
    const base = { node: 'node-1', status: 'WARN', reason: 'kubeProxyCertificateExpiring' };
    const first = present({ totalNodes: 1, warningNodes: 1, nodes: [{ ...base, notAfter: '2026-11-01', daysRemaining: 17 }] });
    const next = present({ totalNodes: 1, warningNodes: 1, nodes: [{ ...base, notAfter: '2026-11-01', daysRemaining: 16 }] });
    expect(first.summary).not.toBe(next.summary);
    expect(first.identity).toBe(next.identity);
    expect(first.identity).not.toContain('2026-11-01');
  });

  it('shows an initial query failure or an empty cluster explicitly', () => {
    expect(present({ totalNodes: 0, warningNodes: 0, reason: 'kubeProxyNoNodes' }).summary).toContain('no se encontraron nodos');
    expect(present({ totalNodes: 0, warningNodes: 0, reason: 'kubectlTimeout' }).summary).toContain('se agotó el tiempo de espera de kubectl');
  });

  it('shows successful coverage and provides translations for every new template key', () => {
    expect(present({ totalNodes: 2, warningNodes: 0, nodes: [] }).summary).toBe('0 de 2 nodos con aviso');
    for (const [key, value] of Object.entries(EN_TRANSLATIONS)) {
      if (key.includes('kubeProxy') || key.includes('kubernetesKubeProxy')) {
        expect(value).not.toBe('');
        expect((ES_UY_TRANSLATIONS as Readonly<Record<string, string>>)[key]).toBeTruthy();
      }
    }
  });
});
