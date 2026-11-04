ALTER TABLE core.alerts
    ADD COLUMN execution_mode varchar(16) NOT NULL DEFAULT 'STANDALONE',
    ADD CONSTRAINT ck_alerts_execution_mode CHECK (execution_mode IN ('STANDALONE', 'PIPE'));

ALTER TABLE audit.alerts_aud ADD COLUMN execution_mode varchar(16);

CREATE INDEX idx_pipe_executions_latest ON core.pipe_executions (pipe_id, started_at DESC, id DESC);
