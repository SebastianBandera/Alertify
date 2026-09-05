UPDATE core.alert_executions execution
SET status_message = execution.status_message - 'writtenValue'
WHERE execution.status_message ? 'writtenValue'
    AND EXISTS (
        SELECT 1
        FROM core.alerts alert
        JOIN core.alert_templates template
            ON template.id = alert.alert_template_id
        JOIN core.alert_parameter_values parameter_value
            ON parameter_value.alert_id = alert.id
        JOIN core.alert_template_parameters parameter
            ON parameter.id = parameter_value.template_parameter_id
        WHERE alert.id = execution.alert_id
            AND template.template_key = 'app.alertify.alerts.templates.devtools.WritableParameterCopyAlertTemplate'
            AND parameter.parameter_key IN ('sourceValue', 'writableValue')
            AND parameter_value.source = 'SECRET'
    );
