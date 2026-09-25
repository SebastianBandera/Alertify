-- Hooks and Pipes receive independent tag namespaces and audited assignments.
ALTER TABLE core.tags DROP CONSTRAINT ck_tags_scope;
ALTER TABLE core.tags ADD CONSTRAINT ck_tags_scope CHECK (
    scope IN ('CONFIGURATION', 'SECRET', 'ALERT', 'PROCEDURE', 'HOOK', 'PIPE')
);

CREATE TABLE core.hook_tag (
    hook_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    tag_scope varchar(32) NOT NULL DEFAULT 'HOOK',
    CONSTRAINT pk_hook_tag PRIMARY KEY (hook_id, tag_id),
    CONSTRAINT ck_hook_tag_scope CHECK (tag_scope = 'HOOK'),
    CONSTRAINT fk_hook_tag_hook FOREIGN KEY (hook_id) REFERENCES core.hooks (id) ON DELETE CASCADE,
    CONSTRAINT fk_hook_tag_tag_scope FOREIGN KEY (tag_id, tag_scope) REFERENCES core.tags (id, scope) ON DELETE RESTRICT
);

CREATE INDEX idx_hook_tag_tag ON core.hook_tag (tag_id, hook_id);

CREATE TABLE core.pipe_tag (
    pipe_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    tag_scope varchar(32) NOT NULL DEFAULT 'PIPE',
    CONSTRAINT pk_pipe_tag PRIMARY KEY (pipe_id, tag_id),
    CONSTRAINT ck_pipe_tag_scope CHECK (tag_scope = 'PIPE'),
    CONSTRAINT fk_pipe_tag_pipe FOREIGN KEY (pipe_id) REFERENCES core.pipes (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipe_tag_tag_scope FOREIGN KEY (tag_id, tag_scope) REFERENCES core.tags (id, scope) ON DELETE RESTRICT
);

CREATE INDEX idx_pipe_tag_tag ON core.pipe_tag (tag_id, pipe_id);

CREATE TABLE audit.hook_tag_aud (
    hook_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    rev bigint NOT NULL,
    revtype smallint,
    CONSTRAINT pk_hook_tag_aud PRIMARY KEY (hook_id, tag_id, rev),
    CONSTRAINT fk_hook_tag_aud_rev FOREIGN KEY (rev) REFERENCES audit.revinfo (rev)
);

CREATE INDEX idx_hook_tag_aud_rev ON audit.hook_tag_aud (rev);

CREATE TABLE audit.pipe_tag_aud (
    pipe_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    rev bigint NOT NULL,
    revtype smallint,
    CONSTRAINT pk_pipe_tag_aud PRIMARY KEY (pipe_id, tag_id, rev),
    CONSTRAINT fk_pipe_tag_aud_rev FOREIGN KEY (rev) REFERENCES audit.revinfo (rev)
);

CREATE INDEX idx_pipe_tag_aud_rev ON audit.pipe_tag_aud (rev);

INSERT INTO audit.log_events (code) VALUES
    ('HOOK_TAG_CREATED'),
    ('HOOK_TAG_DELETED'),
    ('HOOK_TAG_UPDATED'),
    ('PIPE_TAG_CREATED'),
    ('PIPE_TAG_DELETED'),
    ('PIPE_TAG_UPDATED')
ON CONFLICT DO NOTHING;
