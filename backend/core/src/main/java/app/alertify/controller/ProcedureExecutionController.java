package app.alertify.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.procedures.api.ProcedureExecutionResponse;
import app.alertify.procedures.execution.ProcedureExecutionStatus;
import app.alertify.procedures.service.ProcedureExecutionQueryService;

/** Read-only endpoint over the procedure execution history. */
@RestController
@RequestMapping("/api/procedure-executions")
@PreAuthorize("hasRole('ADMIN')")
public class ProcedureExecutionController {
    private final ProcedureExecutionQueryService service;

    public ProcedureExecutionController(ProcedureExecutionQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Page<ProcedureExecutionResponse> search(@RequestParam(required = false) Long procedureId, @RequestParam(required = false) ProcedureExecutionStatus status, @RequestParam(required = false) UUID executionId, @PageableDefault(size = 20, sort = "startedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return service.search(procedureId, status, executionId, pageable);
    }
}
