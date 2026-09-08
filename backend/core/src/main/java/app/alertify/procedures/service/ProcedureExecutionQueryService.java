package app.alertify.procedures.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.configuration.service.SearchValidation;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.api.ProcedureExecutionResponse;
import app.alertify.procedures.execution.ProcedureExecutionStatus;

/**
 * Paged, read-only access to procedure execution history. Sorting is restricted
 * to an allowed set of fields so a client cannot sort by an arbitrary column.
 */
@Service
public class ProcedureExecutionQueryService {
    private static final Set<String> SORT_FIELDS = Set.of("id", "status", "startedAt", "finishedAt", "depth");
    private final ProcedureExecutionRepository repository;
    private final ApplicationEventLogger eventLogger;

    public ProcedureExecutionQueryService(ProcedureExecutionRepository repository, ApplicationEventLogger eventLogger) {
        this.repository = repository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<ProcedureExecutionResponse> search(Long procedureId, ProcedureExecutionStatus status, UUID executionId, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        Page<ProcedureExecutionResponse> result;
        if (executionId != null)
            result = repository.findAllByExecutionId(executionId, pageable).map(ProcedureMapper::toExecution);
        else if (procedureId != null && status != null)
            result = repository.findAllByProcedure_IdAndStatus(procedureId, status, pageable).map(ProcedureMapper::toExecution);
        else if (procedureId != null)
            result = repository.findAllByProcedure_Id(procedureId, pageable).map(ProcedureMapper::toExecution);
        else if (status != null)
            result = repository.findAllByStatus(status, pageable).map(ProcedureMapper::toExecution);
        else
            result = repository.findAll(pageable).map(ProcedureMapper::toExecution);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("totalElements", result.getTotalElements());
        if (executionId != null)
            data.put("executionId", executionId);

        eventLogger.success("PROCEDURE_EXECUTION_HISTORY_VIEWED", data);
        return result;
    }
}
