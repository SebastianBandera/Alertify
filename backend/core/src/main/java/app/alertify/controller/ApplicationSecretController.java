package app.alertify.controller;

import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
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

import app.alertify.secret.api.SecretCreateRequest;
import app.alertify.secret.api.SecretResponse;
import app.alertify.secret.api.SecretUpdateRequest;
import app.alertify.services.secret.ApplicationSecretService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/secrets")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ApplicationSecretController {

    private final ApplicationSecretService service;

    public ApplicationSecretController(ApplicationSecretService service) {
        this.service = service;
    }

    @GetMapping
    public Page<SecretResponse> search(@RequestParam MultiValueMap<String, String> params, @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return service.search(params, pageable);
    }

    @GetMapping("/{id}")
    public SecretResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<SecretResponse> create(@Valid @RequestBody SecretCreateRequest request) {
        SecretResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public SecretResponse update(@PathVariable Long id, @Valid @RequestBody SecretUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
