ALTER TABLE core.alerts
    ADD COLUMN smart_execution_interval_source varchar(16) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN smart_execution_interval_configuration_id bigint,
    ADD CONSTRAINT fk_alerts_smart_interval_configuration FOREIGN KEY (smart_execution_interval_configuration_id)
        REFERENCES core.configurations(id) ON DELETE SET NULL;

CREATE INDEX idx_alerts_smart_interval_configuration ON core.alerts(smart_execution_interval_configuration_id);

ALTER TABLE audit.alerts_aud
    ADD COLUMN smart_execution_interval_source varchar(16),
    ADD COLUMN smart_execution_interval_configuration_id bigint;

ALTER TABLE core.alerts DROP CONSTRAINT ck_alerts_smart_execution;
ALTER TABLE core.alerts ADD CONSTRAINT ck_alerts_smart_execution CHECK (
    smart_execution_interval_source IN ('FIXED', 'CONFIGURATION')
    AND (
        (NOT smart_execution_enabled AND smart_execution_interval_hours IS NULL
            AND smart_execution_policy IS NULL AND smart_execution_interval_configuration_id IS NULL)
        OR (smart_execution_enabled AND smart_execution_policy IS NOT NULL
            AND smart_execution_policy IN ('ONCE_PER_INTERVAL', 'NORMAL', 'ON_ERROR', 'ON_WARN', 'ON_ERROR_OR_WARN')
            AND (
                (smart_execution_interval_source = 'FIXED' AND smart_execution_interval_hours IS NOT NULL
                    AND smart_execution_interval_hours >= 1 AND smart_execution_interval_configuration_id IS NULL)
                OR (smart_execution_interval_source = 'CONFIGURATION' AND smart_execution_interval_hours IS NULL)
            ))
    )
);

-- Operational state, separate from the versioned alert definition and append-only history.
CREATE TABLE core.smart_execution_interval_incidents (
    alert_id bigint PRIMARY KEY REFERENCES core.alerts(id) ON DELETE CASCADE,
    problem varchar(128)
);
