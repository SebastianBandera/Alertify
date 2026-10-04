package app.alertify.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.alerts.service.ResourceResultObserverService;
import app.alertify.alerts.service.ResourceResultObserverService.ResourceOption;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AlertObserverResourcesController {
    private final ResourceResultObserverService service;

    public AlertObserverResourcesController(ResourceResultObserverService service) { this.service = service; }

    @GetMapping("/api/alerts/observer-resources")
    public List<ResourceOption> options(@RequestParam String kind) { return service.options(kind); }
}
