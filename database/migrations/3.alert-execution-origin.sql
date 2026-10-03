ALTER TABLE core.alert_executions DROP CONSTRAINT ck_alert_executions_trigger;
ALTER TABLE core.alert_executions ADD CONSTRAINT ck_alert_executions_trigger
    CHECK (trigger IS NULL OR trigger IN ('CRON', 'MANUAL', 'HOOK', 'SMART', 'PIPE'));
ALTER TABLE core.alert_executions ADD COLUMN parent_pipe_execution_id uuid;
ALTER TABLE core.alert_executions ADD COLUMN parent_step_key text;

-- The step result is authoritative, unlike the historical free-text actor.
UPDATE core.alert_executions alert
SET trigger = 'PIPE', parent_pipe_execution_id = pipe.execution_id, parent_step_key = step.step_key
FROM core.pipe_step_results step
JOIN core.pipe_executions pipe ON pipe.id = step.pipe_execution_id
WHERE step.step_type = 'ALERT' AND step.resource_execution_id = alert.execution_id;

ALTER TABLE core.alert_executions ADD CONSTRAINT ck_alert_executions_pipe_parent
    CHECK ((parent_pipe_execution_id IS NULL AND parent_step_key IS NULL)
        OR (trigger = 'PIPE' AND parent_pipe_execution_id IS NOT NULL AND parent_step_key IS NOT NULL));
CREATE INDEX idx_alert_executions_pipe_parent ON core.alert_executions (parent_pipe_execution_id)
    WHERE parent_pipe_execution_id IS NOT NULL;
