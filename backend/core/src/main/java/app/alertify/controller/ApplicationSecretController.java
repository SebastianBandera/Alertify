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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import app.alertify.secret.api.DatabaseSecretTestRequest;
import app.alertify.secret.api.DatabaseSecretTestResponse;
import app.alertify.secret.api.SecretCreateRequest;
import app.alertify.secret.api.SecretExpressionSuggestionsResponse;
import app.alertify.secret.api.SecretExpressionValidationRequest;
import app.alertify.secret.api.SecretResponse;
import app.alertify.secret.api.SecretUpdateRequest;
import app.alertify.secret.api.BinarySecretCreateRequest;
import app.alertify.secret.api.BinarySecretUpdateRequest;
import app.alertify.services.secret.ApplicationSecretService;
import app.alertify.services.secret.DatabaseSecretProbeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Administrative HTTP API for secret metadata and write-only secret values.
 * No endpoint in this controller returns decrypted or encrypted value bytes.
 */
@RestController
@RequestMapping("/api/secrets")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ApplicationSecretController {

    private final ApplicationSecretService service;
    private final DatabaseSecretProbeService databaseProbeService;

    public ApplicationSecretController(ApplicationSecretService service, DatabaseSecretProbeService databaseProbeService) {
        this.service = service;
        this.databaseProbeService = databaseProbeService;
    }

    @GetMapping
    public Page<SecretResponse> search(@RequestParam MultiValueMap<String, String> params, @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return service.search(params, pageable);
    }

    @GetMapping("/expression-suggestions")
    public SecretExpressionSuggestionsResponse expressionSuggestions() {
        return service.expressionSuggestions();
    }

    @PostMapping("/validate-expression")
    public ResponseEntity<Void> validateExpression(@Valid @RequestBody SecretExpressionValidationRequest request) {
        service.validateExpression(request);
        return ResponseEntity.noContent().build();
    }

    /** Opens a connection with unsaved DB_SECRET credentials from a worker; nothing is stored. */
    @PostMapping("/test-database")
    public DatabaseSecretTestResponse testDatabase(@Valid @RequestBody DatabaseSecretTestRequest request) {
        return databaseProbeService.test(service.parseDatabaseCredentials(request.value()));
    }

    /** Opens a connection with the credentials of a stored DB_SECRET from a worker. */
    @PostMapping("/{id}/test-database")
    public DatabaseSecretTestResponse testStoredDatabase(@PathVariable Long id) {
        ApplicationSecretService.StoredDatabaseCredentials stored = service.databaseCredentials(id);
        return databaseProbeService.test(stored.credentials(), stored.id(), stored.name());
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SecretResponse> createBinary(@Valid @RequestPart("metadata") BinarySecretCreateRequest request, @RequestPart(value = "file", required = false) MultipartFile file) {
        SecretResponse response = service.createBinary(request, file);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public SecretResponse update(@PathVariable Long id, @Valid @RequestBody SecretUpdateRequest request) {
        return service.update(id, request);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SecretResponse updateBinary(@PathVariable Long id, @Valid @RequestPart("metadata") BinarySecretUpdateRequest request, @RequestPart(value = "file", required = false) MultipartFile file) { 
        return service.updateBinary(id, request, file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
