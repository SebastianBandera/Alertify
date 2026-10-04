-- Move the existing table with its data, constraints, indexes and owned identity sequence.
-- The trigger retains its function reference while that function moves to the same schema.
ALTER TABLE core.alert_execution_closure_audit SET SCHEMA audit;
ALTER FUNCTION core.reject_closure_audit_mutation() SET SCHEMA audit;
