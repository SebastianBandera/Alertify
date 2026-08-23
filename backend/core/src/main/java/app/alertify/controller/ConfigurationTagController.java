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

import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.configuration.service.ConfigurationTagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Administrative HTTP API for tags belonging exclusively to configuration
 * records.
 */
@RestController
@RequestMapping("/api/configuration-tags")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ConfigurationTagController {

    private final ConfigurationTagService service;

    public ConfigurationTagController(ConfigurationTagService service) {
        this.service = service;
    }

    @GetMapping
    public Page<TagResponse> search(@RequestParam MultiValueMap<String, String> params, @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return service.search(params, pageable);
    }

    @GetMapping("/{id}")
    public TagResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<TagResponse> create(@Valid @RequestBody TagCreateRequest request) {
        TagResponse response = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    public TagResponse update(@PathVariable Long id, @Valid @RequestBody TagUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
