-- Existing tool rows have no reliable structured approval identity or timestamp.
ALTER TABLE core.ai_tool_invocations
    ADD COLUMN approval_decision varchar(24) NOT NULL DEFAULT 'NOT_RECORDED',
    ADD COLUMN approval_actor_type varchar(24),
    ADD COLUMN approval_actor text,
    ADD COLUMN approval_actor_subject text,
    ADD COLUMN approval_decided_at timestamptz;
