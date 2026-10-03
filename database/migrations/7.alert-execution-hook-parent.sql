ALTER TABLE core.alert_executions ADD COLUMN parent_hook_invocation_id uuid;
ALTER TABLE core.alert_executions ADD COLUMN parent_hook_name text;

-- Invocation targets identify the parent without interpreting the free-text actor.
-- The invocation's snapshot name survives later Hook renames and deletions.
UPDATE core.alert_executions alert
SET parent_hook_invocation_id = invocation.invocation_id,
    parent_hook_name = invocation.hook_name
FROM core.hook_invocation_targets target
JOIN core.hook_invocations invocation ON invocation.id = target.hook_invocation_id
WHERE target.target_type = 'ALERT' AND target.execution_id = alert.execution_id
    AND alert.trigger = 'HOOK' AND alert.parent_pipe_execution_id IS NULL;

ALTER TABLE core.alert_executions ADD CONSTRAINT ck_alert_executions_hook_parent
    CHECK ((parent_hook_invocation_id IS NULL AND parent_hook_name IS NULL)
        OR (trigger IS NOT NULL AND trigger = 'HOOK' AND parent_hook_invocation_id IS NOT NULL
            AND parent_hook_name IS NOT NULL AND btrim(parent_hook_name) <> ''
            AND parent_pipe_execution_id IS NULL AND parent_step_key IS NULL));
CREATE INDEX idx_alert_executions_hook_parent ON core.alert_executions (parent_hook_invocation_id)
    WHERE parent_hook_invocation_id IS NOT NULL;
