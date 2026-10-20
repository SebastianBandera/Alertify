CREATE TABLE core.playwright_wizard_drafts (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    document TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX playwright_wizard_drafts_owner_updated_idx
    ON core.playwright_wizard_drafts (owner_subject, updated_at DESC);
