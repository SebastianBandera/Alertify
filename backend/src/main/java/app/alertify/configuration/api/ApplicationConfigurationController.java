package app.alertify.configuration.api;

import java.net.URI;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import app.alertify.configuration.service.ApplicationConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/configurations")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ApplicationConfigurationController {

    private final ApplicationConfigurationService service;

    public ApplicationConfigurationController(ApplicationConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public Page<ConfigurationResponse> search(
            @RequestParam MultiValueMap<String, String> params,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return service.search(params, pageable);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv() {
        byte[] csv = service.exportCsv();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("alertify-configurations.csv").build().toString()
            )
            .body(csv);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConfigurationImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return service.importCsv(file);
    }

    @GetMapping("/{id}")
    public ConfigurationResponse get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<ConfigurationResponse> create(
            @Valid @RequestBody ConfigurationCreateRequest request) {
        ConfigurationResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public ConfigurationResponse update(
            @PathVariable Long id,
            @Valid @RequestBody ConfigurationUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
