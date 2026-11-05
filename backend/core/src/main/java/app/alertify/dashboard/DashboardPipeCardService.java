package app.alertify.dashboard;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.service.AlertMapper;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.TagResponse;
import app.alertify.jpa.repository.*;
import app.alertify.pipes.api.PipeStepResultResponse;
import app.alertify.pipes.model.*;
import app.alertify.pipes.service.PipeMapper;

@Service
@Transactional(readOnly = true)
public class DashboardPipeCardService {
    private final PipeRepository pipes;
    private final PipeExecutionRepository executions;
    private final AlertExecutionRepository alerts;
    private final NamedParameterJdbcTemplate jdbc;
    private final DashboardPipeExecutionQuery executionQuery;

    public DashboardPipeCardService(PipeRepository pipes, PipeExecutionRepository executions, AlertExecutionRepository alerts, NamedParameterJdbcTemplate jdbc, DashboardPipeExecutionQuery executionQuery) {
        this.pipes = pipes;
        this.executions = executions;
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.executionQuery = executionQuery;
    }

    public PipePage page(int number, int size) {
        var page = pipes.findAll(PageRequest.of(Math.max(0, number), Math.clamp(size, 1, 50), Sort.by("id")));
        return new PipePage(cards(page.getContent()), new DashboardPageResponse.PageMetadata(page.getSize(), page.getNumber(), page.getTotalElements(), page.getTotalPages()));
    }

    public Optional<DashboardPipeCardResponse> card(long id) {
        return pipes.findById(id).map(pipe -> cards(List.of(pipe)).getFirst());
    }

    public PipeExecution execution(long pipeId, UUID executionId) {
        PipeExecution execution = executions.findByExecutionId(executionId).orElseThrow(() -> new ResourceNotFoundException("Pipe execution not found"));
        if (execution.getPipe().getId() != pipeId)
            throw new ResourceNotFoundException("Pipe execution not found");

        execution.getSteps().size();
        return execution;
    }

    private List<DashboardPipeCardResponse> cards(List<Pipe> definitions) {
        if (definitions.isEmpty())
            return List.of();

        Instant observed = Instant.now();
        List<Long> ids = definitions.stream().map(Pipe::getId).toList();
        Map<Long, DashboardPipeExecutionQuery.State> states = executionQuery.states(ids);
        List<Long> latest = jdbc.queryForList("select distinct on (pipe_id) id from core.pipe_executions where pipe_id in (:ids) order by pipe_id, started_at desc, id desc", Map.of("ids", ids), Long.class);
        Map<Long, PipeExecution> byPipe = executions.findAllById(latest).stream().collect(Collectors.toMap(value -> value.getPipe().getId(), value -> value));
        List<UUID> childIds = byPipe.values().stream().flatMap(value -> value.getSteps().stream()).filter(value -> value.getStepType() == PipeStepType.ALERT && value.getResourceExecutionId() != null).map(PipeStepResult::getResourceExecutionId).toList();
        Map<UUID, AlertExecutionResponse> children = new HashMap<>();
        if (!childIds.isEmpty()) {
            List<Long> executionIds = jdbc.queryForList("select id from core.alert_executions where execution_id in (:ids)", Map.of("ids", childIds), Long.class);
            alerts.findAllById(executionIds).forEach(value -> children.put(value.getExecutionId(), AlertMapper.toExecution(value)));
        }
        return definitions.stream().map(pipe -> {
            PipeExecution execution = byPipe.get(pipe.getId());
            List<DashboardPipeCardResponse.Step> steps;
            if (execution == null) {
                steps = pipe.getSteps().stream().map(step -> new DashboardPipeCardResponse.Step(
                        new PipeStepResultResponse(step.getStepKey(), step.getPosition(), step.getStepType(), step.getPhase(), step.getStepType() == PipeStepType.ALERT ? step.getAlert().getId() : step.getProcedure().getId(), step.getStepType() == PipeStepType.ALERT ? step.getAlert().getName() : step.getProcedure().getName(), PipeStepStatus.PENDING, null, null, null, null, null),
                        step.getStepType() == PipeStepType.ALERT ? step.getAlert().getTemplate().getTemplateKey() : step.getProcedure().getTemplate().getTemplateKey(), null)).toList();
            } else {
                steps = PipeMapper.execution(execution).steps().stream().map(step -> {
                    var child = children.get(step.resourceExecutionId());
                    // An alert's template is immutable; never associate a result by current step position.
                    String template = child == null ? null : alerts.findById(child.id()).orElseThrow().getAlert().getTemplate().getTemplateKey();
                    return new DashboardPipeCardResponse.Step(step, template, child);
                }).toList();
            }
            Set<TagResponse> tags = pipe.getTags().stream().map(tag -> new TagResponse(tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(), tag.getColor(), tag.getCreatedAt(), tag.getUpdatedAt())).collect(Collectors.toCollection(LinkedHashSet::new));
            DashboardPipeExecutionQuery.State state = states.get(pipe.getId());
            return new DashboardPipeCardResponse(pipe.getId(), pipe.getVersion(), pipe.getName(), pipe.getDescription(), pipe.isEnabled(), pipe.isConcurrentExecutionAllowed(), tags, execution == null ? null : PipeMapper.execution(execution), steps, state == null ? null : state.outcome(), state == null ? null : state.changedAt(), observed);
        }).toList();
    }

    public record PipePage(List<DashboardPipeCardResponse> content, DashboardPageResponse.PageMetadata page) {
        public PipePage forViewer() { return new PipePage(content.stream().map(DashboardPipeCardResponse::forViewer).toList(), page); }
    }
}
