import { Alert, AlertExecution, AlertExecutionStatus } from '../../core/api/alert-api.service';

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
  /** Length in days of the look-back window behind `previousIssue` and `history`, set by a system configuration. */
  readonly historyWindowDays: number;
  /** Latest WARN and ERROR of an alert whose issues persist until seen; null when the option is off or none happened. */
  readonly persistentIssues: DashboardIssueTimes | null;
}

/** When an alert with persistent issues last finished a WARN and an ERROR (the same for every user). */
export interface DashboardIssueTimes {
  readonly lastWarnAt: string | null;
  readonly lastErrorAt: string | null;
}

/** The worst issue a user has not marked as seen yet, and when it happened. */
export interface PendingIssue {
  readonly status: 'WARN' | 'ERROR';
  readonly at: string;
}

/** Resolves the pending issue of a tile for the current user, or null when there is none. */
export type PendingIssueResolver = (card: DashboardAlertCard) => PendingIssue | null;

/**
 * The worst issue of an alert with persistent issues that happened after the
 * user last marked it as seen; an ERROR outranks a WARN. Never marked counts
 * as nothing seen yet.
 */
export function pendingIssue(card: DashboardAlertCard, acknowledgedAt: string | undefined): PendingIssue | null {
  const issues = card.persistentIssues;
  if (issues === null || card.alert.persistentIssuesSince === null) return null;
  const seen = acknowledgedAt === undefined ? Number.NEGATIVE_INFINITY : Date.parse(acknowledgedAt);
  const after = (at: string | null): at is string => at !== null && Date.parse(at) > seen;
  if (after(issues.lastErrorAt)) return { status: 'ERROR', at: issues.lastErrorAt };
  if (after(issues.lastWarnAt)) return { status: 'WARN', at: issues.lastWarnAt };
  return null;
}

/** Status the tile shows: the last result, raised to an unseen issue when that one is worse. */
export function effectiveStatus(card: DashboardAlertCard, pending: PendingIssue | null): AlertExecutionStatus | null {
  const current = card.lastExecution?.status ?? null;
  if (pending === null) return current;
  return current === null || STATUS_ORDER[pending.status] < STATUS_ORDER[current] ? pending.status : current;
}

/* Worst status first; alerts that never ran carry no signal and close the board. */
const STATUS_ORDER: Readonly<Record<AlertExecutionStatus, number>> = { ERROR: 0, WARN: 1, SUCCESS: 2 };
const NEVER_EXECUTED_ORDER = 3;

/**
 * Instant the alert entered the status it shows, or 0 when it never ran. An
 * unseen issue that raises the tile dates it from when that issue happened.
 */
export function stateChangedAt(card: DashboardAlertCard, pending: PendingIssue | null = null): number {
  const status = effectiveStatus(card, pending);
  if (pending !== null && status === pending.status && status !== card.lastExecution?.status) return Date.parse(pending.at);
  if (card.lastExecution === null) return 0;
  return Date.parse(card.history?.currentStatusSince ?? card.lastExecution.finishedAt);
}

/**
 * Board order: by severity of the status shown (unseen issues included), then
 * most recent state change first, then by name.
 */
export function compareDashboardCards(
  left: DashboardAlertCard,
  right: DashboardAlertCard,
  pendingOf: PendingIssueResolver = () => null,
): number {
  const leftPending = pendingOf(left);
  const rightPending = pendingOf(right);
  const order = (card: DashboardAlertCard, pending: PendingIssue | null): number => {
    const status = effectiveStatus(card, pending);
    return status === null ? NEVER_EXECUTED_ORDER : STATUS_ORDER[status];
  };
  return order(left, leftPending) - order(right, rightPending)
    || stateChangedAt(right, rightPending) - stateChangedAt(left, leftPending)
    || left.alert.name.localeCompare(right.alert.name);
}
