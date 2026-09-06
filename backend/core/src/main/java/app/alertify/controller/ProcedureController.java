package app.alertify.controller;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import app.alertify.procedures.api.ProcedureBindingOptionsResponse;
import app.alertify.procedures.api.ProcedureCreateRequest;
import app.alertify.procedures.api.ProcedureDeletionImpactResponse;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.api.ProcedureImportResult;
import app.alertify.procedures.service.ProcedureCsvService;
import app.alertify.procedures.api.ProcedureUpdateRequest;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.procedures.service.ProcedureManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Administration endpoints for procedures: search, CRUD, CSV import/export,
 * manual runs, and the impact preview shown before a deletion.
 */
@RestController
@RequestMapping("/api/procedures")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ProcedureController {
    private final ProcedureManagementService service;
    private final ProcedureCatalogService catalog;
    private final ProcedureCsvService csvService;

    public ProcedureController(ProcedureManagementService service, ProcedureCatalogService catalog,
            ProcedureCsvService csvService) {
        this.service = service;
        this.catalog = catalog;
        this.csvService = csvService;
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("alertify-procedures.csv").build().toString())
                .body(csvService.exportCsv());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProcedureImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return csvService.importCsv(file);
    }

    @GetMapping
    public Page<ProcedureResponse> search(@RequestParam(required = false) String name, @RequestParam(required = false) @Positive Long templateId, @RequestParam(required = false) List<@Positive Long> tagId, @RequestParam(defaultValue = "OR") @Pattern(regexp = "(?i)OR|AND") String tagOperator, @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return service.search(name, templateId, tagId == null ? java.util.Set.of() : new LinkedHashSet<>(tagId),
                "AND".equalsIgnoreCase(tagOperator), pageable);
    }

    @GetMapping("/binding-options")
    public ProcedureBindingOptionsResponse bindingOptions() { return catalog.bindingOptions(); }

    @GetMapping("/{id}/deletion-impact")
    public ProcedureDeletionImpactResponse deletionImpact(@PathVariable Long id) { return service.deletionImpact(id); }

    @PostMapping
    public ResponseEntity<ProcedureResponse> create(@Valid @RequestBody ProcedureCreateRequest request) {
        ProcedureResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public ProcedureResponse update(@PathVariable Long id, @Valid @RequestBody ProcedureUpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Void> run(@PathVariable Long id) {
        service.runNow(id);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
