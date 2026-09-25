-- Alerts can keep a past WARN or ERROR on the dashboard until each user marks it as seen.
-- Null means the option is off; otherwise only issues after this instant count, so
-- enabling it on an old alert does not resurrect issues from long ago.
ALTER TABLE core.alerts
    ADD COLUMN persistent_issues_since timestamptz;

ALTER TABLE audit.alerts_aud
    ADD COLUMN persistent_issues_since timestamptz;

-- One row per user and alert: the instant up to which that user has seen its issues.
-- Kept apart from core.alerts so acknowledging never bumps the alert's edit version.
CREATE TABLE core.alert_issue_acknowledgements (
    alert_id bigint NOT NULL,
    user_subject text NOT NULL,
    acknowledged_at timestamptz NOT NULL,
    CONSTRAINT pk_alert_issue_acknowledgements PRIMARY KEY (alert_id, user_subject),
    CONSTRAINT ck_alert_issue_acknowledgements_subject CHECK (btrim(user_subject) <> ''),
    CONSTRAINT fk_alert_issue_acknowledgements_alert FOREIGN KEY (alert_id)
        REFERENCES core.alerts (id) ON DELETE CASCADE
);

CREATE INDEX idx_alert_issue_acknowledgements_subject
    ON core.alert_issue_acknowledgements (user_subject);

INSERT INTO audit.log_events (code) VALUES
    ('ALERT_ISSUES_ACKNOWLEDGED')
ON CONFLICT DO NOTHING;
