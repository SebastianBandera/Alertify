-- Secret value types: STRING keeps the historical single-text behaviour, DB_SECRET stores the
-- canonical JSON of a database connection (engine, host, port, database, username, password, options).
ALTER TABLE secrets.secrets
    ADD COLUMN value_type varchar(32) NOT NULL DEFAULT 'STRING',
    ADD CONSTRAINT ck_secrets_value_type CHECK (value_type IN ('STRING', 'DB_SECRET'));

CREATE INDEX idx_secrets_value_type ON secrets.secrets (value_type);

ALTER TABLE audit.secrets_aud
    ADD COLUMN value_type varchar(32);
