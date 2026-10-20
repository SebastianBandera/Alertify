-- Persistent, user-private AI conversations, reusable memories and tool diagnostics.

CREATE TABLE core.ai_conversations (
    id bigserial PRIMARY KEY,
    user_subject text NOT NULL,
    username text NOT NULL,
    title text NOT NULL,
    mode varchar(16) NOT NULL DEFAULT 'READ_ONLY',
    approval_policy varchar(16) NOT NULL DEFAULT 'ASK',
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_ai_conversations_subject CHECK (btrim(user_subject) <> ''),
    CONSTRAINT ck_ai_conversations_username CHECK (btrim(username) <> ''),
    CONSTRAINT ck_ai_conversations_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_ai_conversations_mode CHECK (mode IN ('READ_ONLY', 'PLAN', 'DIRECT')),
    CONSTRAINT ck_ai_conversations_approval CHECK (approval_policy IN ('ASK', 'AUTO')),
    CONSTRAINT ck_ai_conversations_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_ai_conversations_user_updated ON core.ai_conversations (user_subject, updated_at DESC, id DESC);

CREATE TABLE core.ai_turns (
    id bigserial PRIMARY KEY,
    conversation_id bigint NOT NULL REFERENCES core.ai_conversations(id) ON DELETE CASCADE,
    status varchar(24) NOT NULL,
    model varchar(128) NOT NULL,
    reasoning_effort varchar(16) NOT NULL,
    reasoning_upgraded boolean NOT NULL DEFAULT false,
    rejection_count integer NOT NULL DEFAULT 0,
    error_code varchar(64),
    error_message text,
    model_request_count integer NOT NULL DEFAULT 0,
    input_tokens bigint NOT NULL DEFAULT 0,
    cached_input_tokens bigint NOT NULL DEFAULT 0,
    cache_write_input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    reasoning_output_tokens bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    last_request_total_tokens bigint NOT NULL DEFAULT 0,
    model_context_window bigint,
    quota_snapshot jsonb,
    usage_observed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    CONSTRAINT ck_ai_turns_status CHECK (status IN ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_ai_turns_model CHECK (btrim(model) <> ''),
    CONSTRAINT ck_ai_turns_effort CHECK (reasoning_effort IN ('low', 'medium', 'high', 'xhigh')),
    CONSTRAINT ck_ai_turns_rejections CHECK (rejection_count BETWEEN 0 AND 3),
    CONSTRAINT ck_ai_turns_request_count CHECK (model_request_count >= 0),
    CONSTRAINT ck_ai_turns_token_usage CHECK (input_tokens >= 0 AND cached_input_tokens >= 0
        AND cache_write_input_tokens >= 0 AND output_tokens >= 0 AND reasoning_output_tokens >= 0
        AND total_tokens >= 0 AND last_request_total_tokens >= 0),
    CONSTRAINT ck_ai_turns_context_window CHECK (model_context_window IS NULL OR model_context_window > 0),
    CONSTRAINT ck_ai_turns_quota_snapshot CHECK (quota_snapshot IS NULL OR jsonb_typeof(quota_snapshot) = 'object'),
    CONSTRAINT ck_ai_turns_terminal_time CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL))
);

CREATE INDEX idx_ai_turns_conversation ON core.ai_turns (conversation_id, id);

CREATE TABLE core.ai_messages (
    id bigserial PRIMARY KEY,
    conversation_id bigint NOT NULL REFERENCES core.ai_conversations(id) ON DELETE CASCADE,
    turn_id bigint REFERENCES core.ai_turns(id) ON DELETE CASCADE,
    role varchar(16) NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_ai_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    CONSTRAINT ck_ai_messages_content CHECK (content <> '')
);

CREATE INDEX idx_ai_messages_conversation ON core.ai_messages (conversation_id, id);

CREATE TABLE core.ai_tool_invocations (
    id bigserial PRIMARY KEY,
    turn_id bigint NOT NULL REFERENCES core.ai_turns(id) ON DELETE CASCADE,
    call_id text NOT NULL,
    generation bigint NOT NULL,
    tool_name text NOT NULL,
    kind varchar(16) NOT NULL,
    declared boolean NOT NULL,
    allowed boolean NOT NULL,
    arguments text NOT NULL,
    result text,
    status varchar(24) NOT NULL,
    read_only boolean NOT NULL,
    destructive_hint boolean NOT NULL,
    idempotent_hint boolean NOT NULL,
    open_world_hint boolean NOT NULL,
    refresh_targets text NOT NULL DEFAULT '',
    review_reason text,
    diagnostic_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    CONSTRAINT ck_ai_tool_invocations_generation CHECK (generation > 0),
    CONSTRAINT ck_ai_tool_invocations_kind CHECK (kind IN ('DOMAIN', 'INTERNAL', 'UNKNOWN')),
    CONSTRAINT ck_ai_tool_invocations_status CHECK (status IN ('PENDING_APPROVAL', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED', 'INTERRUPTED')),
    CONSTRAINT ck_ai_tool_invocations_terminal_time CHECK ((status IN ('SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED', 'INTERRUPTED')) = (finished_at IS NOT NULL))
);

CREATE INDEX idx_ai_tool_invocations_turn ON core.ai_tool_invocations (turn_id, id);
CREATE INDEX idx_ai_tool_invocations_call ON core.ai_tool_invocations (turn_id, generation, call_id);

-- AI-assisted operations retain the real user and add independent provenance.
-- Conversation IDs are opaque application identifiers and intentionally have no FK.

ALTER TABLE audit.revinfo
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_revinfo_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE audit.logs
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_logs_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE audit.alert_execution_closure_audit
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_alert_closure_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE core.alert_executions
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_alert_executions_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE core.procedure_executions
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_procedure_executions_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

ALTER TABLE core.pipe_executions
    ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_conversation_id bigint,
    ADD CONSTRAINT ck_pipe_executions_ai_conversation
        CHECK (ai_conversation_id IS NULL OR (ai_assisted AND ai_conversation_id > 0));

CREATE INDEX idx_logs_ai_conversation ON audit.logs (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_revinfo_ai_conversation ON audit.revinfo (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_alert_closure_ai_conversation ON audit.alert_execution_closure_audit (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_alert_executions_ai_conversation ON core.alert_executions (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_procedure_executions_ai_conversation ON core.procedure_executions (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;
CREATE INDEX idx_pipe_executions_ai_conversation ON core.pipe_executions (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;

INSERT INTO audit.log_events (code) VALUES
    ('AI_CONVERSATION_DELETED'),
    ('AI_TOOL_COMPLETED'),
    ('AI_TOOL_FAILED'),
    ('AI_TOOL_STARTED')
ON CONFLICT (code) DO NOTHING;

-- User-private reusable knowledge, independent of conversation retention.
CREATE TABLE core.ai_memories (
    id bigserial PRIMARY KEY,
    user_subject text NOT NULL,
    memory_key varchar(96) NOT NULL,
    content text NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    source_conversation_id bigint REFERENCES core.ai_conversations(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_ai_memories_user_key UNIQUE (user_subject, memory_key),
    CONSTRAINT ck_ai_memories_subject CHECK (btrim(user_subject) <> ''),
    CONSTRAINT ck_ai_memories_key CHECK (memory_key ~ '^[a-z0-9]+([._-][a-z0-9]+)*$'),
    CONSTRAINT ck_ai_memories_content CHECK (btrim(content) <> '' AND char_length(content) <= 4000),
    CONSTRAINT ck_ai_memories_version CHECK (version >= 0)
);

INSERT INTO audit.log_events (code) VALUES ('AI_MEMORY_SAVED'), ('AI_MEMORY_DELETED')
ON CONFLICT (code) DO NOTHING;
