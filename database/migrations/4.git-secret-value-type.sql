-- GIT_SECRET stores the canonical JSON of Git hosting credentials (provider, host,
-- optional username, token, optional token expiry), encrypted like any other value.

ALTER TABLE secrets.secrets
    DROP CONSTRAINT ck_secrets_value_type;

ALTER TABLE secrets.secrets
    ADD CONSTRAINT ck_secrets_value_type CHECK (
        value_type IN ('STRING', 'DB_SECRET', 'GIT_SECRET', 'EXPRESSION', 'BINARY')
    );
