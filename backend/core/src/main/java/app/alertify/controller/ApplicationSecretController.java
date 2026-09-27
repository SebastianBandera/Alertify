package app.alertify.controller;

import java.net.URI;
import java.util.List;

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
import app.alertify.secret.api.SecretMetadataUpdateRequest;
import app.alertify.secret.api.SecretResponse;
import app.alertify.secret.api.SecretUpdateRequest;
import app.alertify.secret.api.SecretUsagesResponse;
import app.alertify.secret.api.BinarySecretCreateRequest;
import app.alertify.secret.api.BinarySecretUpdateRequest;
import app.alertify.services.secret.ApplicationSecretService;
import app.alertify.services.secret.DatabaseSecretProbeService;
import app.alertify.services.secret.SecretUsageService;
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
    private final SecretUsageService usageService;

    public ApplicationSecretController(ApplicationSecretService service, DatabaseSecretProbeService databaseProbeService, SecretUsageService usageService) {
        this.service = service;
        this.databaseProbeService = databaseProbeService;
        this.usageService = usageService;
    }

    @GetMapping
    public Page<SecretResponse> search(@RequestParam MultiValueMap<String, String> params, @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        Page<SecretResponse> page = service.search(params, pageable);
        var usages = usageService.getForIds(page.getContent().stream().map(SecretResponse::id).toList());
        return page.map(secret -> secret.withUsageCount(usages.get(secret.id()).totalCount()));
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
        return withUsageCount(service.get(id));
    }

    @GetMapping("/{id}/usages")
    public SecretUsagesResponse usages(@PathVariable Long id) {
        return usageService.get(id);
    }

    @PostMapping
    public ResponseEntity<SecretResponse> create(@Valid @RequestBody SecretCreateRequest request) {
        SecretResponse response = withUsageCount(service.create(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SecretResponse> createBinary(@Valid @RequestPart("metadata") BinarySecretCreateRequest request, @RequestPart(value = "file", required = false) MultipartFile file) {
        SecretResponse response = withUsageCount(service.createBinary(request, file));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public SecretResponse update(@PathVariable Long id, @Valid @RequestBody SecretUpdateRequest request) {
        return withUsageCount(service.update(id, request));
    }

    @PutMapping("/{id}/metadata")
    public SecretResponse updateMetadata(@PathVariable Long id, @Valid @RequestBody SecretMetadataUpdateRequest request) {
        return withUsageCount(service.updateMetadata(id, request));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SecretResponse updateBinary(@PathVariable Long id, @Valid @RequestPart("metadata") BinarySecretUpdateRequest request, @RequestPart(value = "file", required = false) MultipartFile file) { 
        return withUsageCount(service.updateBinary(id, request, file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    private SecretResponse withUsageCount(SecretResponse response) {
        return response.withUsageCount(usageService.getForIds(List.of(response.id())).get(response.id()).totalCount());
    }
}
