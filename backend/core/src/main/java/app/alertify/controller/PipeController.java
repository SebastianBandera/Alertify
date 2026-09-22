package app.alertify.controller;

import java.net.URI;
import java.util.UUID;

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

import app.alertify.pipes.api.PipeAcceptedResponse;
import app.alertify.pipes.api.PipeCreateRequest;
import app.alertify.pipes.api.PipeDeletionImpactResponse;
import app.alertify.pipes.api.PipeOptionsResponse;
import app.alertify.pipes.api.PipeImportResult;
import app.alertify.pipes.api.PipeResponse;
import app.alertify.pipes.api.PipeUpdateRequest;
import app.alertify.pipes.service.PipeManagementService;
import app.alertify.pipes.service.PipeCsvService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/pipes")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class PipeController {
    private final PipeManagementService service;
    private final PipeCsvService csvService;

    public PipeController(PipeManagementService service, PipeCsvService csvService) {
        this.service = service;
        this.csvService = csvService;
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("alertify-pipes.csv").build().toString())
                .body(csvService.exportCsv());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PipeImportResult importCsv(@RequestParam("file") MultipartFile file) { return csvService.importCsv(file); }

    @GetMapping
    public Page<PipeResponse> search(@RequestParam(required = false) String name, @PageableDefault(size = 20, sort = "name") Pageable pageable) { return service.search(name, pageable); }

    @GetMapping("/options")
    public PipeOptionsResponse options() { return service.options(); }

    @GetMapping("/{id}")
    public PipeResponse get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<PipeResponse> create(@Valid @RequestBody PipeCreateRequest request) {
        PipeResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public PipeResponse update(@PathVariable long id, @Valid @RequestBody PipeUpdateRequest request) { return service.update(id, request); }

    @PostMapping("/{id}/run")
    public ResponseEntity<PipeAcceptedResponse> run(@PathVariable long id) {
        UUID executionId = service.run(id);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/pipe-executions/{id}").buildAndExpand(executionId).toUri();
        return ResponseEntity.accepted().location(location).body(new PipeAcceptedResponse(executionId));
    }

    @GetMapping("/{id}/deletion-impact")
    public PipeDeletionImpactResponse deletionImpact(@PathVariable long id) { return service.deletionImpact(id); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
