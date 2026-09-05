ALTER TABLE secrets.secrets
    ADD COLUMN writable boolean NOT NULL DEFAULT false;

ALTER TABLE audit.secrets_aud
    ADD COLUMN writable boolean;

INSERT INTO audit.log_events (code) VALUES
    ('SECRET_OVERWRITTEN_BY_ALERT'),
    ('SECRET_OVERWRITE_REJECTED');
