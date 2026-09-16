-- Lets templates declare parameters whose backing configuration or secret is
-- mutable template state. Existing templates retain their historical behavior.

ALTER TABLE core.alert_template_parameters
    ADD COLUMN writable_binding_required boolean NOT NULL DEFAULT false;

ALTER TABLE core.alert_template_parameters
    ADD CONSTRAINT ck_alert_template_parameters_writable_binding CHECK (
        NOT writable_binding_required
        OR (
            binding_allowed
            AND NOT allowed_sources ?| ARRAY['TEXT', 'PROCEDURE']
            AND allowed_sources <@ '["CONFIGURATION","SECRET"]'::jsonb
        )
    );

ALTER TABLE audit.alert_template_parameters_aud
    ADD COLUMN writable_binding_required boolean;
