package app.alertify.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.configuration.api.TagResponse;
import app.alertify.pipes.api.PipeExecutionResponse;
import app.alertify.pipes.api.PipeStepResultResponse;

/** No binding expressions, parameters, tokens or procedure outputs in a live tile. */
public record DashboardPipeCardResponse(long id, long version, String name, String description, boolean enabled, boolean allowConcurrentExecutions, Set<TagResponse> tags, PipeExecutionResponse execution, List<Step> steps, Instant observedAt) {
    public DashboardPipeCardResponse forViewer() {
        return new DashboardPipeCardResponse(id, version, name, description, enabled, allowConcurrentExecutions, tags, execution,
                steps.stream().map(step -> new Step(step.result(), step.templateKey(), step.alertExecution() == null ? null : step.alertExecution().forViewer())).toList(), observedAt);
    }

    public record Step(PipeStepResultResponse result, String templateKey, AlertExecutionResponse alertExecution) { }
}
