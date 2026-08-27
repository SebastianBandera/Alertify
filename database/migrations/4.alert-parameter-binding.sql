ALTER TABLE core.alert_template_parameters
    ADD COLUMN binding_allowed boolean NOT NULL DEFAULT true,
    ADD COLUMN default_value text;

ALTER TABLE core.alert_template_parameters
    ALTER COLUMN binding_allowed DROP DEFAULT,
    DROP COLUMN allowed_sources,
    ADD CONSTRAINT ck_alert_template_parameters_nonbinding_options CHECK (
        binding_allowed OR jsonb_array_length(options) > 0
    ),
    ADD CONSTRAINT ck_alert_template_parameters_nonbinding_default CHECK (
        default_value IS NULL
        OR binding_allowed
        OR options ? default_value
    );

ALTER TABLE audit.alert_template_parameters_aud
    ADD COLUMN binding_allowed boolean,
    ADD COLUMN default_value text;

UPDATE audit.alert_template_parameters_aud
SET binding_allowed = true
WHERE allowed_sources IS NOT NULL;

ALTER TABLE audit.alert_template_parameters_aud
    DROP COLUMN allowed_sources;
