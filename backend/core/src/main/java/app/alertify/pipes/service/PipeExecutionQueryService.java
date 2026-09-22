package app.alertify.pipes.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.PipeExecutionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.api.PipeExecutionResponse;

@Service
public class PipeExecutionQueryService {
    private final PipeExecutionRepository repository;
    private final ApplicationEventLogger eventLogger;

    public PipeExecutionQueryService(PipeExecutionRepository repository, ApplicationEventLogger eventLogger) {
        this.repository = repository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<PipeExecutionResponse> search(Long pipeId, Pageable pageable) {
        Page<PipeExecutionResponse> result = (pipeId == null ? repository.findAll(pageable)
                : repository.findAllByPipe_Id(pipeId, pageable)).map(PipeMapper::execution);
        eventLogger.success("PIPE_EXECUTION_HISTORY_VIEWED", Map.of("page", result.getNumber(), "size", result.getSize()));
        return result;
    }

    @Transactional(readOnly = true)
    public PipeExecutionResponse get(UUID executionId) {
        PipeExecutionResponse result = repository.findByExecutionId(executionId).map(PipeMapper::execution)
                .orElseThrow(() -> new ResourceNotFoundException("Pipe execution " + executionId + " was not found"));
        eventLogger.success("PIPE_EXECUTION_VIEWED", Map.of("executionId", executionId));
        return result;
    }
}
