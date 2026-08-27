package app.alertify.startup;

import org.springframework.stereotype.Component;

import app.alertify.alerts.template.AlertTemplateRegistrationService;

/**
 * Extension point for startup tasks that must complete before startup is
 * considered successful.
 */
@Component
public class StartupProcess {

    private final AlertTemplateRegistrationService alertTemplateRegistrationService;

    public StartupProcess(AlertTemplateRegistrationService alertTemplateRegistrationService) {
        this.alertTemplateRegistrationService = alertTemplateRegistrationService;
    }

    public void run() {
        alertTemplateRegistrationService.scanAndRegister();
    }
}
