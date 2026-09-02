package app.alertify.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.grpc.api.WorkerNodeStatusResponse;
import app.alertify.grpc.discovery.WorkerStatusService;

@RestController
@RequestMapping("/api/workers/status")
@PreAuthorize("hasRole('ADMIN')")
public class WorkerStatusController {

    private final WorkerStatusService service;

    public WorkerStatusController(WorkerStatusService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkerNodeStatusResponse> status() {
        return service.status();
    }
}
