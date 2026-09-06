package app.alertify.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.procedures.api.ProcedureTemplateResponse;
import app.alertify.procedures.service.ProcedureCatalogService;

/** Read-only endpoint over the registered procedure template catalog. */
@RestController
@RequestMapping("/api/procedure-templates")
@PreAuthorize("hasRole('ADMIN')")
public class ProcedureTemplateController {
    private final ProcedureCatalogService service;

    public ProcedureTemplateController(ProcedureCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProcedureTemplateResponse> templates() {
        return service.templates();
    }
}
