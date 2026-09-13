ALTER TABLE core.configurations DROP CONSTRAINT ck_configurations_value_type;
ALTER TABLE core.configurations DROP CONSTRAINT ck_configurations_value_matches_type;
ALTER TABLE core.configurations ADD COLUMN binary_file_name text;
ALTER TABLE core.configurations ADD COLUMN binary_content_type text;
ALTER TABLE core.configurations ADD COLUMN binary_size bigint;
ALTER TABLE core.configurations ADD COLUMN binary_zip_size bigint;
ALTER TABLE core.configurations ADD COLUMN binary_sha256 bytea;
ALTER TABLE core.configurations ADD CONSTRAINT ck_configurations_value_type CHECK (value_type IN (
    'STRING', 'EXPRESSION', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIME', 'DATE_TIME', 'JSON', 'BINARY'
));
ALTER TABLE core.configurations ADD CONSTRAINT ck_configurations_value_matches_type CHECK (
    (value_type IN ('STRING', 'EXPRESSION', 'DATE', 'TIME', 'DATE_TIME') AND jsonb_typeof(configuration_value) = 'string')
 OR (value_type = 'INTEGER' AND jsonb_typeof(configuration_value) = 'number' AND mod((configuration_value #>> '{}')::numeric, 1) = 0)
 OR (value_type = 'DECIMAL' AND jsonb_typeof(configuration_value) = 'number')
 OR (value_type = 'BOOLEAN' AND jsonb_typeof(configuration_value) = 'boolean')
 OR (value_type = 'JSON' AND jsonb_typeof(configuration_value) IN ('object', 'array'))
 OR (value_type = 'BINARY' AND configuration_value = '{}'::jsonb)
);
ALTER TABLE core.configurations ADD CONSTRAINT ck_configurations_binary_metadata CHECK (
    (value_type = 'BINARY' AND binary_file_name IS NOT NULL AND binary_content_type IS NOT NULL
        AND binary_size > 0 AND binary_zip_size > 0 AND octet_length(binary_sha256) = 32)
 OR (value_type <> 'BINARY' AND binary_file_name IS NULL AND binary_content_type IS NULL
        AND binary_size IS NULL AND binary_zip_size IS NULL AND binary_sha256 IS NULL)
);
CREATE TABLE core.configuration_binary_values (
    configuration_id bigint PRIMARY KEY REFERENCES core.configurations(id) ON DELETE CASCADE,
    zip_value bytea NOT NULL
);

ALTER TABLE secrets.secrets DROP CONSTRAINT ck_secrets_value_type;
ALTER TABLE secrets.secrets ADD COLUMN binary_file_name text;
ALTER TABLE secrets.secrets ADD COLUMN binary_content_type text;
ALTER TABLE secrets.secrets ADD COLUMN binary_size bigint;
ALTER TABLE secrets.secrets ADD COLUMN binary_zip_size bigint;
ALTER TABLE secrets.secrets ADD CONSTRAINT ck_secrets_value_type CHECK (value_type IN ('STRING', 'DB_SECRET', 'EXPRESSION', 'BINARY'));
ALTER TABLE secrets.secrets ADD CONSTRAINT ck_secrets_binary_metadata CHECK (
    (value_type = 'BINARY' AND binary_file_name IS NOT NULL AND binary_content_type IS NOT NULL AND binary_size > 0 AND binary_zip_size > 0)
 OR (value_type <> 'BINARY' AND binary_file_name IS NULL AND binary_content_type IS NULL AND binary_size IS NULL AND binary_zip_size IS NULL)
);
CREATE TABLE secrets.secret_binary_values (
    secret_id bigint PRIMARY KEY REFERENCES secrets.secrets(id) ON DELETE CASCADE,
    encrypted_value bytea NOT NULL,
    encryption_iv bytea NOT NULL,
    value_hash bytea NOT NULL,
    hash_salt bytea NOT NULL,
    encryption_version smallint NOT NULL,
    CONSTRAINT ck_secret_binary_iv_length CHECK (octet_length(encryption_iv) = 12),
    CONSTRAINT ck_secret_binary_hash_length CHECK (octet_length(value_hash) = 32),
    CONSTRAINT ck_secret_binary_salt_length CHECK (octet_length(hash_salt) = 16)
);

ALTER TABLE audit.configurations_aud ADD COLUMN binary_file_name text;
ALTER TABLE audit.configurations_aud ADD COLUMN binary_content_type text;
ALTER TABLE audit.configurations_aud ADD COLUMN binary_size bigint;
ALTER TABLE audit.configurations_aud ADD COLUMN binary_zip_size bigint;
ALTER TABLE audit.configurations_aud ADD COLUMN binary_sha256 bytea;
ALTER TABLE audit.secrets_aud ADD COLUMN binary_file_name text;
ALTER TABLE audit.secrets_aud ADD COLUMN binary_content_type text;
ALTER TABLE audit.secrets_aud ADD COLUMN binary_size bigint;
ALTER TABLE audit.secrets_aud ADD COLUMN binary_zip_size bigint;
