package app.alertify.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.systemconfiguration.api.SystemConfigurationRegenerateRequest;
import app.alertify.systemconfiguration.api.SystemConfigurationResponse;
import app.alertify.systemconfiguration.api.SystemConfigurationUpdateRequest;
import app.alertify.systemconfiguration.service.SystemConfigurationService;
import jakarta.validation.Valid;

/**
 * Administrative HTTP API for listing and updating system configurations.
 * There is no create or delete endpoint: entries are seeded by migration.
 */
@RestController
@RequestMapping("/api/system-configurations")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class SystemConfigurationController {

    private final SystemConfigurationService service;

    public SystemConfigurationController(SystemConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public Page<SystemConfigurationResponse> search(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return service.search(pageable);
    }

    @GetMapping("/{id}")
    public SystemConfigurationResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public SystemConfigurationResponse update(@PathVariable Long id, @Valid @RequestBody SystemConfigurationUpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/regenerate")
    public SystemConfigurationResponse regenerate(@PathVariable Long id, @Valid @RequestBody SystemConfigurationRegenerateRequest request) {
        return service.regenerate(id, request);
    }
}
