package app.alertify.alerts.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.templates.ResourceResultObserverAlertTemplate;
import app.alertify.api.error.InvalidAlertRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ResourceResultObserverService {
    public static final String TEMPLATE = ResourceResultObserverAlertTemplate.class.getName();
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public ResourceResultObserverService(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean supports(String template) { return TEMPLATE.equals(template); }

    @Transactional(readOnly = true)
    public List<ResourceOption> options(String kind) {
        return jdbc.query("select id, name, enabled from core." + table(kind) + " order by lower(name), id",
                (row, index) -> new ResourceOption(row.getLong("id"), row.getString("name"), row.getBoolean("enabled")));
    }

    /** The reference and its restrictive foreign key are replaced atomically with alert parameters. */
    public void synchronize(Alert alert, List<AlertParameterValue> values) {
        if (!supports(alert.getTemplate().getTemplateKey()))
            return;

        Map<String, String> parameters = values.stream().collect(Collectors.toMap(value -> value.getTemplateParameter().getParameterKey(), AlertParameterValue::getTextValue));
        String kind = parameters.get("resourceKind");
        String table = table(kind);
        long id;
        try {
            id = Long.parseLong(parameters.get("resourceId"));
        } catch (RuntimeException exception) {
            throw new InvalidAlertRequestException("Observed resource id must be a positive integer");
        }
        if (id <= 0 || !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from core." + table + " where id = ?)", Boolean.class, id)))
            throw new InvalidAlertRequestException("Observed resource was not found");

        jdbc.update("""
                insert into core.alert_resource_observers (alert_id, pipe_id, procedure_id, hook_id)
                values (?, ?, ?, ?)
                on conflict (alert_id) do update set pipe_id = excluded.pipe_id, procedure_id = excluded.procedure_id, hook_id = excluded.hook_id
                """, alert.getId(), "PIPE".equals(kind) ? id : null, "PROCEDURE".equals(kind) ? id : null, "HOOK".equals(kind) ? id : null);
    }

    @Transactional(readOnly = true)
    public ObservedResult observe(long alertId) {
        List<Resource> resources = jdbc.query("""
                select case when pipe_id is not null then 'PIPE' when procedure_id is not null then 'PROCEDURE' else 'HOOK' end as kind,
                       coalesce(pipe_id, procedure_id, hook_id) as id
                from core.alert_resource_observers where alert_id = ?
                """, (row, index) -> new Resource(row.getString("kind"), row.getLong("id")), alertId);
        if (resources.isEmpty())
            throw new IllegalStateException("Resource observer reference was not found");

        Resource resource = resources.getFirst();
        String sql = switch (resource.kind()) {
            case "PIPE" -> "select execution_id, status, outcome as severity, finished_at from core.pipe_executions where pipe_id = ? and finished_at is not null order by finished_at desc, id desc limit 1";
            case "PROCEDURE" -> "select execution_id, status, case when status = 'ERROR' then 'ERROR' else 'SUCCESS' end as severity, finished_at from core.procedure_executions where procedure_id = ? and finished_at is not null order by finished_at desc, id desc limit 1";
            case "HOOK" -> """
                    select invocation_id as execution_id, status,
                        case when status = 'FAILED' or exists(select 1 from core.hook_invocation_targets t where t.hook_invocation_id = h.id and t.status = 'ERROR') then 'ERROR'
                             when status = 'PARTIAL' or exists(select 1 from core.hook_invocation_targets t where t.hook_invocation_id = h.id and t.status = 'WARN') then 'WARN' else 'SUCCESS' end as severity,
                        finished_at from core.hook_invocations h where hook_id = ? and finished_at is not null order by finished_at desc, id desc limit 1
                    """;
            default -> throw new IllegalStateException("Invalid observer resource kind");
        };
        List<Terminal> results = jdbc.query(sql, (row, index) -> new Terminal(row.getObject("execution_id", UUID.class),
                row.getString("status"), row.getString("severity"), row.getObject("finished_at", OffsetDateTime.class).toInstant()), resource.id());
        var summary = mapper.createObjectNode().put("resourceKind", resource.kind()).put("resourceId", resource.id());
        if (results.isEmpty())
            return new ObservedResult(AlertExecutionStatus.WARN, summary.put("status", "NO_TERMINAL_RESULT"));

        Terminal terminal = results.getFirst();
        summary.put("executionId", terminal.id().toString()).put("status", terminal.status()).put("finishedAt", terminal.finishedAt().toString());
        AlertExecutionStatus status = terminal.severity() == null ? AlertExecutionStatus.ERROR : AlertExecutionStatus.valueOf(terminal.severity());
        summary.put("outcome", status.name());
        return new ObservedResult(status, summary);
    }

    private static String table(String kind) {
        return switch (kind == null ? "" : kind) {
            case "PIPE" -> "pipes";
            case "PROCEDURE" -> "procedures";
            case "HOOK" -> "hooks";
            default -> throw new InvalidAlertRequestException("Observed resource kind must be PIPE, PROCEDURE or HOOK");
        };
    }

    public record ObservedResult(AlertExecutionStatus status, JsonNode summary) { }
    public record ResourceOption(long id, String name, boolean enabled) { }
    private record Resource(String kind, long id) { }
    private record Terminal(UUID id, String status, String severity, Instant finishedAt) { }
}
