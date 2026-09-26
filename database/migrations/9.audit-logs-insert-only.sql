CREATE FUNCTION audit.reject_logs_modification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit.logs is insert-only; % is not allowed', TG_OP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER audit_logs_insert_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON audit.logs
FOR EACH STATEMENT
EXECUTE FUNCTION audit.reject_logs_modification();
