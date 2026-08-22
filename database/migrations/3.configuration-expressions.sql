ALTER TABLE core.configurations
    DROP CONSTRAINT ck_configurations_value_type;

ALTER TABLE core.configurations
    ADD CONSTRAINT ck_configurations_value_type CHECK (value_type IN (
        'STRING', 'EXPRESSION', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATE_TIME', 'JSON'
    ));

ALTER TABLE core.configurations
    DROP CONSTRAINT ck_configurations_value_matches_type;

ALTER TABLE core.configurations
    ADD CONSTRAINT ck_configurations_value_matches_type CHECK (
        (value_type IN ('STRING', 'EXPRESSION', 'DATE', 'DATE_TIME')
            AND jsonb_typeof(configuration_value) = 'string')
        OR (value_type = 'INTEGER'
            AND jsonb_typeof(configuration_value) = 'number'
            AND mod((configuration_value #>> '{}')::numeric, 1) = 0)
        OR (value_type = 'DECIMAL'
            AND jsonb_typeof(configuration_value) = 'number')
        OR (value_type = 'BOOLEAN'
            AND jsonb_typeof(configuration_value) = 'boolean')
        OR (value_type = 'JSON'
            AND jsonb_typeof(configuration_value) IN ('object', 'array'))
    );

CREATE TABLE core.configuration_expression_dependencies (
    configuration_id bigint NOT NULL,
    referenced_configuration_id bigint NOT NULL,
    CONSTRAINT pk_configuration_expression_dependencies PRIMARY KEY (
        configuration_id, referenced_configuration_id
    ),
    CONSTRAINT fk_configuration_expression_dependencies_configuration
        FOREIGN KEY (configuration_id) REFERENCES core.configurations (id) ON DELETE CASCADE,
    CONSTRAINT fk_configuration_expression_dependencies_referenced_configuration
        FOREIGN KEY (referenced_configuration_id) REFERENCES core.configurations (id) ON DELETE RESTRICT
);

CREATE INDEX idx_configuration_expression_dependencies_referenced
    ON core.configuration_expression_dependencies (referenced_configuration_id, configuration_id);

INSERT INTO audit.log_events (code) VALUES
    ('CONFIGURATION_EXPRESSION_EVALUATED');
