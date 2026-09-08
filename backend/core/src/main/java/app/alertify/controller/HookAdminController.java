package app.alertify.controller;

import java.net.URI;

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

import app.alertify.hooks.api.HookCreateRequest;
import app.alertify.hooks.api.HookDeletionImpactResponse;
import app.alertify.hooks.api.HookInvocationResponse;
import app.alertify.hooks.api.HookOptionsResponse;
import app.alertify.hooks.api.HookResponse;
import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.service.HookInvocationPersistenceService;
import app.alertify.hooks.service.HookManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/hooks")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class HookAdminController {

    private final HookManagementService service;
    private final HookInvocationPersistenceService invocations;

    public HookAdminController(HookManagementService service, HookInvocationPersistenceService invocations) {
        this.service = service;
        this.invocations = invocations;
    }

    @GetMapping
    public Page<HookResponse> search(@RequestParam(required = false) String name, @PageableDefault(size = 20, sort = "name") Pageable pageable) { return service.search(name, pageable); }

    @GetMapping("/options")
    public HookOptionsResponse options() { return service.options(); }

    @GetMapping("/{id:\\d+}")
    public HookResponse get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<HookResponse> create(@Valid @RequestBody HookCreateRequest request) {
        HookResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id:\\d+}")
    public HookResponse update(@PathVariable Long id, @Valid @RequestBody HookUpdateRequest request) { return service.update(id, request); }

    @PostMapping("/{id:\\d+}/rotate")
    public HookResponse rotate(@PathVariable Long id, @RequestParam @PositiveOrZero long version) { return service.rotate(id, version); }

    @GetMapping("/{id:\\d+}/deletion-impact")
    public HookDeletionImpactResponse deletionImpact(@PathVariable Long id) { return service.deletionImpact(id); }

    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id:\\d+}/invocations")
    public Page<HookInvocationResponse> history(@PathVariable Long id, @PageableDefault(size = 20, sort = "acceptedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        service.get(id);
        return invocations.history(id, pageable);
    }
}
