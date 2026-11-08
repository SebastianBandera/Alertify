ALTER TABLE core.procedures
    ADD COLUMN execution_mode varchar(16) NOT NULL DEFAULT 'STANDALONE',
    ADD CONSTRAINT ck_procedures_execution_mode CHECK (execution_mode IN ('STANDALONE', 'PIPE'));

ALTER TABLE audit.procedures_aud ADD COLUMN execution_mode varchar(16);
