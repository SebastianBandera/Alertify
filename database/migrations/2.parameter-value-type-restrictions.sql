-- Adds an optional, template-declared restriction on which
-- ConfigurationValueType/SecretValueType a parameter may bind to, and gives
-- alert template parameters the same allowed_sources restriction that
-- procedure template parameters already had. Empty arrays/the historical
-- default preserve the exact behaviour existing templates already had.

ALTER TABLE core.alert_template_parameters
    ADD COLUMN allowed_sources jsonb NOT NULL DEFAULT '["TEXT","CONFIGURATION","SECRET"]'::jsonb,
    ADD COLUMN allowed_configuration_value_types jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN allowed_secret_value_types jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE core.alert_template_parameters
    ADD CONSTRAINT ck_alert_template_parameters_allowed_sources CHECK (
        jsonb_typeof(allowed_sources) = 'array' AND jsonb_array_length(allowed_sources) > 0
    ),
    ADD CONSTRAINT ck_alert_template_parameters_allowed_configuration_value_types CHECK (
        jsonb_typeof(allowed_configuration_value_types) = 'array'
    ),
    ADD CONSTRAINT ck_alert_template_parameters_allowed_secret_value_types CHECK (
        jsonb_typeof(allowed_secret_value_types) = 'array'
    );

ALTER TABLE core.procedure_template_parameters
    ADD COLUMN allowed_configuration_value_types jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN allowed_secret_value_types jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE core.procedure_template_parameters
    ADD CONSTRAINT ck_procedure_template_parameters_allowed_configuration_value_types CHECK (
        jsonb_typeof(allowed_configuration_value_types) = 'array'
    ),
    ADD CONSTRAINT ck_procedure_template_parameters_allowed_secret_value_types CHECK (
        jsonb_typeof(allowed_secret_value_types) = 'array'
    );

ALTER TABLE audit.alert_template_parameters_aud
    ADD COLUMN allowed_sources jsonb,
    ADD COLUMN allowed_configuration_value_types jsonb,
    ADD COLUMN allowed_secret_value_types jsonb;

ALTER TABLE audit.procedure_template_parameters_aud
    ADD COLUMN allowed_configuration_value_types jsonb,
    ADD COLUMN allowed_secret_value_types jsonb;
