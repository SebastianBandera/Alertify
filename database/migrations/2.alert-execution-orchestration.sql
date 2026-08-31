ALTER TABLE core.alert_templates
    ADD COLUMN source_path text NOT NULL DEFAULT '',
    ADD COLUMN allow_concurrent_executions boolean NOT NULL DEFAULT false;

ALTER TABLE core.alert_templates
    ADD CONSTRAINT ck_alert_templates_source_path_trimmed CHECK (
        source_path = btrim(source_path)
    );

ALTER TABLE audit.alert_templates_aud
    ADD COLUMN source_path text,
    ADD COLUMN allow_concurrent_executions boolean;

ALTER TABLE core.alert_executions
    ADD COLUMN work_started_at timestamptz;

UPDATE core.alert_executions
SET work_started_at = started_at
WHERE work_started_at IS NULL;

ALTER TABLE core.alert_executions
    ALTER COLUMN work_started_at SET NOT NULL,
    DROP CONSTRAINT ck_alert_executions_time,
    ADD CONSTRAINT ck_alert_executions_time CHECK (
        work_started_at >= started_at AND finished_at >= work_started_at
    );

INSERT INTO audit.log_events (code) VALUES
    ('ALERT_EXECUTION_COMPLETED'),
    ('ALERT_EXECUTION_DISPATCH_FAILED'),
    ('ALERT_EXECUTION_SKIPPED'),
    ('ALERT_EXECUTION_STARTED'),
    ('ALERT_SCHEDULE_REGISTERED'),
    ('ALERT_SCHEDULE_REMOVED'),
    ('WORKER_STATUS_VIEWED');
