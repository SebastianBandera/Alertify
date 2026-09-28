ALTER TABLE core.alerts
    DROP CONSTRAINT ck_alerts_smart_execution;

ALTER TABLE core.alerts
    ADD CONSTRAINT ck_alerts_smart_execution CHECK (
        (NOT smart_execution_enabled
            AND smart_execution_interval_hours IS NULL
            AND smart_execution_policy IS NULL)
        OR (smart_execution_enabled
            AND smart_execution_interval_hours >= 1
            AND smart_execution_policy IN (
                'ONCE_PER_INTERVAL', 'NORMAL', 'ON_ERROR', 'ON_WARN', 'ON_ERROR_OR_WARN'
            ))
    );
