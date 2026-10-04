import { AlertChartDefinition, AlertChartExtractors } from './alert-chart-definition';
import { EXTENDED_ALERT_CHART_EXTRACTORS } from './alert-chart-extractors.extended';

const TEMPLATES = 'app.alertify.alerts.templates.';
const finite = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null;

const internet: AlertChartDefinition = { labelKey: 'chart.latency', unit: 'ms', extract: ({ result }) => finite(result['latencyMs']) };
const tcp: AlertChartDefinition = { labelKey: 'chart.connection', unit: 'ms', extract: ({ result }) => finite(result['connectMs']) };

/** Template-specific result contracts match their bundled message formatters. */
export const ALERT_CHART_EXTRACTORS: AlertChartExtractors = {
  [`${TEMPLATES}SqlThresholdAlertTemplate`]: { labelKey: 'chart.value', extract: ({ result }) => finite(result['value']) },
  [`${TEMPLATES}SqlWatchAlertTemplate`]: { labelKey: 'chart.rows', extract: ({ result }) => finite(result['rows']) },
  [`${TEMPLATES}HttpsCertificateExpiryAlertTemplate`]: { labelKey: 'chart.daysRemaining', unit: 'd', extract: ({ result }) => finite(result['daysRemaining']) },
  [`${TEMPLATES}InternetConnectionAlertTemplate`]: internet,
  [`${TEMPLATES}WebRequestAlertTemplate`]: internet,
  [`${TEMPLATES}TcpConnectionAlertTemplate`]: tcp,
  [`${TEMPLATES}DatabaseConnectionAlertTemplate`]: tcp,
  [`${TEMPLATES}KubernetesWorkloadAlertTemplate`]: { labelKey: 'chart.restarts', extract: ({ result }) => finite(result['restartTotal']) },
  [`${TEMPLATES}PlaywrightPageAlertTemplate`]: { labelKey: 'chart.load', unit: 'ms', extract: ({ result }) => finite(result['loadDurationMs']) },
  [`${TEMPLATES}devtools.SimulatedLongRunningAlertTemplate`]: { labelKey: 'chart.duration', unit: 'ms', extract: ({ result }) => finite(result['sleepMilliseconds']) },
  [`${TEMPLATES}devtools.SimulatedLongRunningPlaywrightAlertTemplate`]: { labelKey: 'chart.duration', unit: 'ms', extract: ({ result }) => finite(result['sleepMilliseconds']) },
  ...EXTENDED_ALERT_CHART_EXTRACTORS,
};
