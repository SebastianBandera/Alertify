import { Alert, AlertExecution, AlertExecutionStatus } from '../../core/api/alert-api.service';

/** Length of the look-back window summarized on every tile (mirrors the backend). */
export const DASHBOARD_HISTORY_WINDOW_DAYS = 5;

/**
 * Aggregate of the executions inside the look-back window, enough to tell a
 * steadily green alert apart from one that recovered from a recent incident.
 */
export interface DashboardHistorySummary {
  /** Worst status observed inside the window (ERROR > WARN > SUCCESS). */
  readonly worstStatus: AlertExecutionStatus;
  /** When that worst status was last observed. */
  readonly worstStatusLastAt: string;
  /** Start of the uninterrupted streak of the current status. */
  readonly currentStatusSince: string;
}

/**
 * One dashboard tile as served by the backend: an alert together with the
 * outcome of its most recent execution. A `null` execution means the alert
 * never ran yet.
 */
export interface DashboardAlertCard {
  readonly alert: Alert;
  readonly lastExecution: AlertExecution | null;
  /** Most recent WARN or ERROR execution before `lastExecution` inside the look-back window, if any. */
  readonly previousIssue: AlertExecution | null;
  readonly history: DashboardHistorySummary | null;
  /** Start of an execution currently in progress on a worker, if any. */
  readonly runningSince: string | null;
}

/* Worst status first; alerts that never ran carry no signal and close the board. */
const STATUS_ORDER: Readonly<Record<AlertExecutionStatus, number>> = { ERROR: 0, WARN: 1, SUCCESS: 2 };
const NEVER_EXECUTED_ORDER = 3;

/** Instant the alert entered its current status, or 0 when it never ran. */
export function stateChangedAt(card: DashboardAlertCard): number {
  if (card.lastExecution === null) return 0;
  return Date.parse(card.history?.currentStatusSince ?? card.lastExecution.finishedAt);
}

/** Board order: by severity of the last result, then most recent state change first, then by name. */
export function compareDashboardCards(left: DashboardAlertCard, right: DashboardAlertCard): number {
  const order = (card: DashboardAlertCard): number =>
    card.lastExecution ? STATUS_ORDER[card.lastExecution.status] : NEVER_EXECUTED_ORDER;
  return order(left) - order(right)
    || stateChangedAt(right) - stateChangedAt(left)
    || left.alert.name.localeCompare(right.alert.name);
}
