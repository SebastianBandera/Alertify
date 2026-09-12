package app.alertify.startup;

import org.springframework.stereotype.Component;

import app.alertify.alerts.template.AlertTemplateRegistrationService;
import app.alertify.alerts.execution.AlertScheduleService;
import app.alertify.procedures.execution.ProcedureScheduleService;
import app.alertify.procedures.template.ProcedureTemplateRegistrationService;

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

    public StartupProcess(AlertTemplateRegistrationService alertTemplateRegistrationService, AlertScheduleService alertScheduleService, ProcedureTemplateRegistrationService procedureTemplateRegistrationService, ProcedureScheduleService procedureScheduleService) {
        this.alertTemplateRegistrationService = alertTemplateRegistrationService;
        this.alertScheduleService = alertScheduleService;
        this.procedureTemplateRegistrationService = procedureTemplateRegistrationService;
        this.procedureScheduleService = procedureScheduleService;
    }

    public void run() {
        alertTemplateRegistrationService.scanAndRegister();
        procedureTemplateRegistrationService.scanAndRegister();
        alertScheduleService.scheduleAll();
        procedureScheduleService.scheduleAll();
    }
}
