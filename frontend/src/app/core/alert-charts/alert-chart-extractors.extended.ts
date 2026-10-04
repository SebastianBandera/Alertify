import { AlertChartExtractors } from './alert-chart-definition';

/**
 * Entries are full Java template class names. This registry is merged after
 * the bundled registry and may deliberately replace a bundled extractor.
 * Add labels to en.extended.translations.ts and es-uy.extended.translations.ts.
 * Return one finite number, or null when this result has no meaningful value.
 * Extractors are isolated: an exception cannot prevent the modal from opening.
 */
export const EXTENDED_ALERT_CHART_EXTRACTORS: AlertChartExtractors = {
  // 'my.company.QueueAlertTemplate': {
  //   labelKey: 'custom.chart.queueDepth',
  //   extract: ({ result }) => typeof result['queueDepth'] === 'number' ? result['queueDepth'] : null,
  // },
};
