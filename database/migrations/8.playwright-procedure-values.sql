-- Preserve procedure history even when a private wizard draft is deleted.
ALTER TABLE core.procedure_executions ADD COLUMN wizard_draft_id uuid;
ALTER TABLE core.procedure_executions DROP CONSTRAINT ck_procedure_executions_trigger;
ALTER TABLE core.procedure_executions ADD CONSTRAINT ck_procedure_executions_trigger
    CHECK (trigger IN ('MANUAL', 'CRON', 'ALERT', 'PROCEDURE', 'PIPE', 'HOOK', 'WIZARD'));
ALTER TABLE core.procedure_executions DROP CONSTRAINT ck_procedure_executions_parent;
ALTER TABLE core.procedure_executions ADD CONSTRAINT ck_procedure_executions_parent CHECK (
    (trigger IN ('MANUAL', 'CRON', 'HOOK') AND parent_alert_execution_id IS NULL AND parent_procedure_execution_id IS NULL AND parent_pipe_execution_id IS NULL AND wizard_draft_id IS NULL AND depth = 1)
    OR (trigger = 'ALERT' AND parent_alert_execution_id IS NOT NULL AND parent_procedure_execution_id IS NULL AND parent_pipe_execution_id IS NULL AND wizard_draft_id IS NULL)
    OR (trigger = 'PROCEDURE' AND parent_alert_execution_id IS NULL AND parent_procedure_execution_id IS NOT NULL AND parent_pipe_execution_id IS NULL AND wizard_draft_id IS NULL)
    OR (trigger = 'PIPE' AND parent_alert_execution_id IS NULL AND parent_procedure_execution_id IS NULL AND parent_pipe_execution_id IS NOT NULL AND wizard_draft_id IS NULL)
    OR (trigger = 'WIZARD' AND parent_alert_execution_id IS NULL AND parent_procedure_execution_id IS NULL AND parent_pipe_execution_id IS NULL AND wizard_draft_id IS NOT NULL AND depth = 1)
);
CREATE INDEX idx_procedure_executions_wizard_draft ON core.procedure_executions (wizard_draft_id) WHERE wizard_draft_id IS NOT NULL;
