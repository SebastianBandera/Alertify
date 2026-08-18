ALTER TABLE audit.logs
    ADD COLUMN path text;

UPDATE audit.logs
SET path = data ->> 'path'
WHERE data ? 'path'
  AND jsonb_typeof(data -> 'path') = 'string';

CREATE INDEX idx_logs_path ON audit.logs (path);
