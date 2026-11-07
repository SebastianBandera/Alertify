package app.alertify.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import app.alertify.system.SystemStatusService;
import app.alertify.system.BackendStatusService;
import app.alertify.system.api.BackendStatusResponse;
import app.alertify.system.api.SystemStatusSummaryResponse;

@RestController
@RequestMapping("/api/system-status")
@PreAuthorize(app.alertify.config.AuthorizationPolicies.ADMIN)
public class SystemStatusController {

    private final SystemStatusService service;
    private final BackendStatusService backend;

    public SystemStatusController(SystemStatusService service, BackendStatusService backend) {
        this.service = service;
        this.backend = backend;
    }

    @GetMapping("/summary")
    public SystemStatusSummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/backend")
    public ResponseEntity<BackendStatusResponse> backend() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(backend.status());
    }
}
