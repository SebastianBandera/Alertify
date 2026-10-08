ALTER TABLE core.pipe_step_bindings
    ALTER COLUMN target_parameter_id DROP NOT NULL,
    ADD COLUMN target_alert_parameter_id bigint,
    ADD COLUMN value_expression text,
    ADD CONSTRAINT ck_pipe_step_bindings_target
        CHECK ((target_parameter_id IS NOT NULL AND target_alert_parameter_id IS NULL)
            OR (target_parameter_id IS NULL AND target_alert_parameter_id IS NOT NULL)),
    ADD CONSTRAINT ck_pipe_step_bindings_expression_source
        CHECK (value_expression IS NULL OR source_result_pointer IS NOT NULL),
    ADD CONSTRAINT ck_pipe_step_bindings_alert_source
        CHECK (target_alert_parameter_id IS NULL OR source_output_id IS NULL),
    ADD CONSTRAINT fk_pipe_step_bindings_target_alert_parameter
        FOREIGN KEY (target_alert_parameter_id)
        REFERENCES core.alert_template_parameters (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_pipe_step_bindings_target_alert_parameter
    ON core.pipe_step_bindings (target_step_id, target_alert_parameter_id)
    WHERE target_alert_parameter_id IS NOT NULL;

CREATE INDEX idx_pipe_step_bindings_target_alert_parameter
    ON core.pipe_step_bindings (target_alert_parameter_id)
    WHERE target_alert_parameter_id IS NOT NULL;

ALTER TABLE audit.pipe_step_bindings_aud
    ADD COLUMN target_alert_parameter_id bigint,
    ADD COLUMN value_expression text;
