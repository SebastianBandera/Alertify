ALTER TABLE core.configurations
    ADD COLUMN writable boolean NOT NULL DEFAULT false;

ALTER TABLE audit.configurations_aud
    ADD COLUMN writable boolean;

ALTER TABLE core.configurations
    DROP CONSTRAINT ck_configurations_value_type;

ALTER TABLE core.configurations
    ADD CONSTRAINT ck_configurations_value_type CHECK (value_type IN (
        'STRING', 'EXPRESSION', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIME', 'DATE_TIME', 'JSON'
    ));

ALTER TABLE core.configurations
    DROP CONSTRAINT ck_configurations_value_matches_type;

ALTER TABLE core.configurations
    ADD CONSTRAINT ck_configurations_value_matches_type CHECK (
        (value_type IN ('STRING', 'EXPRESSION', 'DATE', 'TIME', 'DATE_TIME')
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

INSERT INTO audit.log_events (code) VALUES
    ('CONFIGURATION_OVERWRITTEN_BY_ALERT'),
    ('CONFIGURATION_OVERWRITE_REJECTED');
