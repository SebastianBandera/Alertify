package app.alertify.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.alerts.service.AlertCatalogService;

@RestController
@RequestMapping("/api/alert-templates")
@PreAuthorize("hasRole('ADMIN')")
public class AlertTemplateController {

    private final AlertCatalogService service;

    public AlertTemplateController(AlertCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<AlertTemplateResponse> templates() {
        return service.templates();
    }
}
