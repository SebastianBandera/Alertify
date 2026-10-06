-- AI-assisted operations retain the real user and add independent provenance.
-- Conversation IDs are opaque application identifiers and intentionally have no FK.

ALTER TABLE audit.revinfo
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_revinfo_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE audit.logs
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_logs_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE audit.alert_execution_closure_audit
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_alert_closure_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE core.alert_executions
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_alert_executions_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE core.procedure_executions
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_procedure_executions_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE core.pipe_executions
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_pipe_executions_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

CREATE INDEX idx_logs_ai_conversation ON audit.logs (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_revinfo_ai_conversation ON audit.revinfo (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_alert_closure_ai_conversation ON audit.alert_execution_closure_audit (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_alert_executions_ai_conversation ON core.alert_executions (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_procedure_executions_ai_conversation ON core.procedure_executions (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_pipe_executions_ai_conversation ON core.pipe_executions (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
