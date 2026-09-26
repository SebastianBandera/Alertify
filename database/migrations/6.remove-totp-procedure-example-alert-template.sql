-- Remove the local example alert template after its implementation was retired.
DO $$
DECLARE
    target_template_key CONSTANT text := 'app.alertify.alerts.templates.custom.TotpProcedureExampleAlertTemplate';
    target_template_id bigint;
BEGIN
    SELECT id
    INTO target_template_id
    FROM core.alert_templates
    WHERE template_key = target_template_key;

    IF target_template_id IS NULL THEN
        RETURN;

    END IF;

    IF EXISTS (SELECT 1 FROM core.alerts WHERE alert_template_id = target_template_id) THEN
        RAISE EXCEPTION 'Cannot remove alert template % because configured alerts still reference it', target_template_key;

    END IF;

    DELETE FROM core.alert_template_parameters
    WHERE alert_template_id = target_template_id;

    DELETE FROM core.alert_templates
    WHERE id = target_template_id;
END
$$;
