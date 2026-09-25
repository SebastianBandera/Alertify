package app.alertify.startup;

import org.springframework.stereotype.Component;

import app.alertify.alerts.template.AlertTemplateRegistrationService;
import app.alertify.alerts.execution.AlertScheduleService;
import app.alertify.procedures.execution.ProcedureScheduleService;
import app.alertify.procedures.template.ProcedureTemplateRegistrationService;
import app.alertify.services.secret.SecretKeyRotationStartupService;

/**
 * Extension point for startup tasks that must complete before startup is
 * considered successful.
 */
@Component
public class StartupProcess {

    private final AlertTemplateRegistrationService alertTemplateRegistrationService;
    private final AlertScheduleService alertScheduleService;
    private final ProcedureTemplateRegistrationService procedureTemplateRegistrationService;
    private final ProcedureScheduleService procedureScheduleService;
    private final SecretKeyRotationStartupService secretKeyRotationStartupService;
    private final BackendStartupAvailability startupAvailability;

    public StartupProcess(AlertTemplateRegistrationService alertTemplateRegistrationService, AlertScheduleService alertScheduleService, ProcedureTemplateRegistrationService procedureTemplateRegistrationService, ProcedureScheduleService procedureScheduleService, SecretKeyRotationStartupService secretKeyRotationStartupService, BackendStartupAvailability startupAvailability) {
        this.alertTemplateRegistrationService = alertTemplateRegistrationService;
        this.alertScheduleService = alertScheduleService;
        this.procedureTemplateRegistrationService = procedureTemplateRegistrationService;
        this.procedureScheduleService = procedureScheduleService;
        this.secretKeyRotationStartupService = secretKeyRotationStartupService;
        this.startupAvailability = startupAvailability;
    }

    public void run() {
        startupAvailability.migrating();
        secretKeyRotationStartupService.initializeAndRotateIfRequired();
        alertTemplateRegistrationService.scanAndRegister();
        procedureTemplateRegistrationService.scanAndRegister();
        alertScheduleService.scheduleAll();
        procedureScheduleService.scheduleAll();
    }
}
