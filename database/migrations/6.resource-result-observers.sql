CREATE TABLE core.alert_resource_observers (
    alert_id bigint PRIMARY KEY REFERENCES core.alerts (id) ON DELETE CASCADE,
    pipe_id bigint REFERENCES core.pipes (id) ON DELETE RESTRICT,
    procedure_id bigint REFERENCES core.procedures (id) ON DELETE RESTRICT,
    hook_id bigint REFERENCES core.hooks (id) ON DELETE RESTRICT,
    CHECK (num_nonnulls(pipe_id, procedure_id, hook_id) = 1)
);
CREATE INDEX idx_resource_observers_pipe ON core.alert_resource_observers (pipe_id) WHERE pipe_id IS NOT NULL;
CREATE INDEX idx_resource_observers_procedure ON core.alert_resource_observers (procedure_id) WHERE procedure_id IS NOT NULL;
CREATE INDEX idx_resource_observers_hook ON core.alert_resource_observers (hook_id) WHERE hook_id IS NOT NULL;

-- Observers carry a structured, sanitized summary also when the observed result is ERROR.
ALTER TABLE core.alert_executions DROP CONSTRAINT ck_alert_executions_error_fields;
ALTER TABLE core.alert_executions ADD CONSTRAINT ck_alert_executions_error_fields CHECK (
    (status IN ('SUCCESS', 'WARN') AND error_type IS NULL AND error_message IS NULL AND error_stack_trace IS NULL)
    OR (status = 'ERROR' AND error_type IS NOT NULL)
);
