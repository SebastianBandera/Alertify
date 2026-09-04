ALTER TABLE core.alert_templates
    ADD COLUMN tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_alert_templates_tags_array CHECK (jsonb_typeof(tags) = 'array');

ALTER TABLE audit.alert_templates_aud
    ADD COLUMN tags jsonb;
