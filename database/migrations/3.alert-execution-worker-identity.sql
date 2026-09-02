ALTER TABLE core.alert_executions
    ADD COLUMN execution_id uuid,
    ADD COLUMN worker_name text,
    ADD COLUMN worker_ip_address varchar(45),
    ADD COLUMN worker_port integer,
    ADD COLUMN worker_instance_id uuid;

UPDATE core.alert_executions
SET execution_id = gen_random_uuid()
WHERE execution_id IS NULL;

ALTER TABLE core.alert_executions
    ALTER COLUMN execution_id SET NOT NULL,
    ADD CONSTRAINT ck_alert_executions_worker_port CHECK (
        worker_port IS NULL OR worker_port BETWEEN 1 AND 65535
    ),
    ADD CONSTRAINT ck_alert_executions_worker_identity CHECK (
        (worker_name IS NULL
            AND worker_ip_address IS NULL
            AND worker_port IS NULL
            AND worker_instance_id IS NULL)
        OR (worker_name IS NOT NULL AND btrim(worker_name) <> ''
            AND worker_ip_address IS NOT NULL AND btrim(worker_ip_address) <> ''
            AND worker_port IS NOT NULL
            AND worker_instance_id IS NOT NULL)
    );

CREATE INDEX idx_alert_executions_execution_id
    ON core.alert_executions (execution_id);

CREATE INDEX idx_alert_executions_worker_instance_started
    ON core.alert_executions (worker_instance_id, started_at DESC)
    WHERE worker_instance_id IS NOT NULL;
