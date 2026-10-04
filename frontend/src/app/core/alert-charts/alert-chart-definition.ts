import { Alert, AlertExecution } from '../api/alert-api.service';

/** The persisted result of this particular template, never a generic JSON path. */
export interface AlertChartContext {
  readonly alert: Alert;
  readonly execution: AlertExecution;
  readonly result: Readonly<Record<string, unknown>>;
}

export interface AlertChartDefinition {
  readonly labelKey: string;
  readonly unit?: string;
  readonly extract: (context: AlertChartContext) => number | null;
}

export type AlertChartExtractors = Readonly<Record<string, AlertChartDefinition>>;
