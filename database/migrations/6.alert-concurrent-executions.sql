ALTER TABLE core.alerts
    ADD COLUMN allow_concurrent_executions boolean NOT NULL DEFAULT false;

ALTER TABLE audit.alerts_aud
    ADD COLUMN allow_concurrent_executions boolean;

UPDATE core.alerts AS alert
SET allow_concurrent_executions = template.allow_concurrent_executions
FROM core.alert_templates AS template
WHERE template.id = alert.alert_template_id;

ALTER TABLE core.alert_templates
    DROP COLUMN allow_concurrent_executions;

ALTER TABLE audit.alert_templates_aud
    DROP COLUMN allow_concurrent_executions;
