-- OIDC_TOKEN_SET stores access, optional refresh and ID tokens plus expiry
-- metadata as canonical JSON encrypted like every other secret value.

ALTER TABLE secrets.secrets
    DROP CONSTRAINT ck_secrets_value_type;

ALTER TABLE secrets.secrets
    ADD CONSTRAINT ck_secrets_value_type CHECK (
        value_type IN ('STRING', 'DB_SECRET', 'GIT_SECRET', 'OIDC_TOKEN_SET', 'EXPRESSION', 'BINARY')
    );
