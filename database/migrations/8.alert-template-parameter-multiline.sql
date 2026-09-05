ALTER TABLE core.alert_template_parameters
    ADD COLUMN multiline boolean NOT NULL DEFAULT false;

ALTER TABLE audit.alert_template_parameters_aud
    ADD COLUMN multiline boolean;
