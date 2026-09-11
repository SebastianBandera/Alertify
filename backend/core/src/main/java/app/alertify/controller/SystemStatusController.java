package app.alertify.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.system.SystemStatusService;
import app.alertify.system.api.SystemStatusSummaryResponse;

@RestController
@RequestMapping("/api/system-status")
@PreAuthorize("hasRole('ADMIN')")
public class SystemStatusController {

    private final SystemStatusService service;

    public SystemStatusController(SystemStatusService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public SystemStatusSummaryResponse summary() {
        return service.summary();
    }
}
