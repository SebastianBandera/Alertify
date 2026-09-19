-- The dashboard summarizes every alert with a handful of "latest execution of
-- this status" lookups. Keyed by status, each of them becomes one index probe
-- regardless of how many executions the alert accumulated.

CREATE INDEX IF NOT EXISTS idx_alert_executions_alert_status_started
    ON core.alert_executions (alert_id, status, started_at DESC);
