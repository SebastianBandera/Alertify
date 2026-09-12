-- EXPRESSION secrets: the encrypted plaintext is a template that may reference other secrets,
-- configurations, environment variables and utilities. Only dependency edges are stored in clear.
ALTER TABLE secrets.secrets DROP CONSTRAINT ck_secrets_value_type;
ALTER TABLE secrets.secrets
    ADD CONSTRAINT ck_secrets_value_type CHECK (value_type IN ('STRING', 'DB_SECRET', 'EXPRESSION'));

CREATE TABLE secrets.secret_expression_dependencies (
    secret_id bigint NOT NULL,
    referenced_secret_id bigint,
    referenced_configuration_id bigint,
    CONSTRAINT ck_secret_expression_dependencies_target
        CHECK (num_nonnulls(referenced_secret_id, referenced_configuration_id) = 1),
    CONSTRAINT fk_secret_expression_dependencies_secret
        FOREIGN KEY (secret_id) REFERENCES secrets.secrets (id) ON DELETE CASCADE,
    CONSTRAINT fk_secret_expression_dependencies_referenced_secret
        FOREIGN KEY (referenced_secret_id) REFERENCES secrets.secrets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_secret_expression_dependencies_referenced_configuration
        FOREIGN KEY (referenced_configuration_id) REFERENCES core.configurations (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_secret_expression_dependencies
    ON secrets.secret_expression_dependencies (secret_id, coalesce(referenced_secret_id, 0), coalesce(referenced_configuration_id, 0));

CREATE INDEX idx_secret_expression_dependencies_referenced_secret
    ON secrets.secret_expression_dependencies (referenced_secret_id, secret_id);

CREATE INDEX idx_secret_expression_dependencies_referenced_configuration
    ON secrets.secret_expression_dependencies (referenced_configuration_id, secret_id);
