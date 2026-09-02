package app.alertify.startup;

import org.springframework.stereotype.Component;

import app.alertify.alerts.template.AlertTemplateRegistrationService;
import app.alertify.alerts.execution.AlertScheduleService;

/**
 * Extension point for startup tasks that must complete before startup is
 * considered successful.
 */
@Component
public class StartupProcess {

    private final AlertTemplateRegistrationService alertTemplateRegistrationService;
    private final AlertScheduleService alertScheduleService;

    public StartupProcess(AlertTemplateRegistrationService alertTemplateRegistrationService, AlertScheduleService alertScheduleService) {
        this.alertTemplateRegistrationService = alertTemplateRegistrationService;
        this.alertScheduleService = alertScheduleService;
    }

    public void run() {
        alertTemplateRegistrationService.scanAndRegister();
        alertScheduleService.scheduleAll();
    }
}
