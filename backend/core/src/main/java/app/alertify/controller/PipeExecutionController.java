package app.alertify.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.pipes.api.PipeExecutionResponse;
import app.alertify.pipes.service.PipeExecutionQueryService;

@RestController
@RequestMapping("/api/pipe-executions")
@PreAuthorize("hasRole('ADMIN')")
public class PipeExecutionController {
    private final PipeExecutionQueryService service;

    public PipeExecutionController(PipeExecutionQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Page<PipeExecutionResponse> search(@RequestParam(required = false) Long pipeId, @PageableDefault(size = 20, sort = "startedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) { return service.search(pipeId, pageable); }

    @GetMapping("/{executionId}")
    public PipeExecutionResponse get(@PathVariable UUID executionId) { return service.get(executionId); }
}
