-- Template-specific default for preserving WARN/ERROR results until each user sees them.
ALTER TABLE core.alert_templates
    ADD COLUMN persistent_issues_default boolean NOT NULL DEFAULT false;

ALTER TABLE audit.alert_templates_aud
    ADD COLUMN persistent_issues_default boolean;
