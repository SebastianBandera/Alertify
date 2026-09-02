ALTER TABLE core.tags
    DROP CONSTRAINT ck_tags_scope;

ALTER TABLE core.tags
    ADD CONSTRAINT ck_tags_scope CHECK (scope IN ('CONFIGURATION', 'SECRET', 'ALERT'));

CREATE TABLE core.alert_tag (
    alert_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    tag_scope varchar(32) NOT NULL DEFAULT 'ALERT',
    CONSTRAINT pk_alert_tag PRIMARY KEY (alert_id, tag_id),
    CONSTRAINT ck_alert_tag_scope CHECK (tag_scope = 'ALERT'),
    CONSTRAINT fk_alert_tag_alert
        FOREIGN KEY (alert_id) REFERENCES core.alerts (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_tag_tag_scope
        FOREIGN KEY (tag_id, tag_scope) REFERENCES core.tags (id, scope) ON DELETE RESTRICT
);

CREATE INDEX idx_alert_tag_tag ON core.alert_tag (tag_id, alert_id);

CREATE TABLE audit.alert_tag_aud (
    alert_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    rev bigint NOT NULL,
    revtype smallint,
    CONSTRAINT pk_alert_tag_aud PRIMARY KEY (alert_id, tag_id, rev),
    CONSTRAINT fk_alert_tag_aud_rev FOREIGN KEY (rev) REFERENCES audit.revinfo (rev)
);

CREATE INDEX idx_alert_tag_aud_rev ON audit.alert_tag_aud (rev);

INSERT INTO audit.log_events (code) VALUES
    ('ALERT_TAG_CREATED'),
    ('ALERT_TAG_DELETED'),
    ('ALERT_TAG_UPDATED');
