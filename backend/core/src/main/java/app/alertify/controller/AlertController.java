package app.alertify.controller;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import app.alertify.alerts.api.AlertBindingOptionsResponse;
import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertStateResponse;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.service.AlertCatalogService;
import app.alertify.alerts.service.AlertManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/alerts")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AlertController {

    private final AlertManagementService service;
    private final AlertCatalogService catalogService;

    public AlertController(AlertManagementService service, AlertCatalogService catalogService) {
        this.service = service;
        this.catalogService = catalogService;
    }

    @GetMapping
    public Page<AlertResponse> search(@RequestParam(required = false) String name,
            @RequestParam(required = false) @Positive Long templateId,
            @RequestParam(required = false) List<@Positive Long> tagId,
            @RequestParam(defaultValue = "OR") @Pattern(regexp = "(?i)OR|AND") String tagOperator,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return service.search(
                name, templateId,
                tagId == null ? java.util.Set.of() : new LinkedHashSet<>(tagId),
                "AND".equalsIgnoreCase(tagOperator), pageable
        );
    }

    @GetMapping("/binding-options")
    public AlertBindingOptionsResponse bindingOptions() {
        return catalogService.bindingOptions();
    }

    @GetMapping("/{id}/state")
    public AlertStateResponse state(@PathVariable Long id) {
        return service.state(id);
    }

    @PostMapping
    public ResponseEntity<AlertResponse> create(@Valid @RequestBody AlertCreateRequest request) {
        AlertResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public AlertResponse update(@PathVariable Long id, @Valid @RequestBody AlertUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
