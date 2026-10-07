ALTER TABLE secrets.secrets
    DROP CONSTRAINT ck_secrets_value_type;

ALTER TABLE secrets.secrets
    ADD CONSTRAINT ck_secrets_value_type CHECK (
        value_type IN ('STRING', 'USERNAME_PASSWORD', 'DB_SECRET', 'GIT_SECRET', 'OIDC_TOKEN_SET', 'KUBECONFIG', 'EXPRESSION', 'BINARY')
    );
