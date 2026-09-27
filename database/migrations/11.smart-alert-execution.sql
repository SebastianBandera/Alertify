ALTER TABLE core.alerts
    ADD COLUMN smart_execution_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN smart_execution_interval_hours integer,
    ADD COLUMN smart_execution_policy varchar(32),
    ADD CONSTRAINT ck_alerts_smart_execution CHECK (
        (NOT smart_execution_enabled
            AND smart_execution_interval_hours IS NULL
            AND smart_execution_policy IS NULL)
        OR (smart_execution_enabled
            AND smart_execution_interval_hours >= 1
            AND smart_execution_policy IN ('NORMAL', 'ON_ERROR', 'ON_WARN', 'ON_ERROR_OR_WARN'))
    );

ALTER TABLE audit.alerts_aud
    ADD COLUMN smart_execution_enabled boolean,
    ADD COLUMN smart_execution_interval_hours integer,
    ADD COLUMN smart_execution_policy varchar(32);

ALTER TABLE core.alert_executions
    DROP CONSTRAINT ck_alert_executions_trigger,
    ADD CONSTRAINT ck_alert_executions_trigger CHECK (
        trigger IS NULL OR trigger IN ('CRON', 'MANUAL', 'HOOK', 'SMART')
    );

CREATE INDEX idx_alerts_smart_execution
    ON core.alerts (id)
    WHERE enabled AND smart_execution_enabled;

CREATE INDEX idx_alert_executions_alert_success_finished
    ON core.alert_executions (alert_id, finished_at DESC)
    WHERE status = 'SUCCESS';

CREATE INDEX idx_alert_executions_alert_trigger_finished
    ON core.alert_executions (alert_id, trigger, finished_at DESC);
