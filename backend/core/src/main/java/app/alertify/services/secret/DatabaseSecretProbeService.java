package app.alertify.services.secret;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import app.alertify.alerts.execution.AlertExecutionPreparationService;
import app.alertify.alerts.execution.PreparedAlertExecution;
import app.alertify.alerts.execution.ResolvedAlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.templates.DatabaseConnectionAlertTemplate;
import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerTemplateSynchronizationException;
import app.alertify.grpc.discovery.SelectedWorker;
import app.alertify.grpc.discovery.WorkerReservation;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.secret.api.DatabaseSecretTestResponse;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.TemplateKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Probes {@code DB_SECRET} credentials before they are saved by running
 * {@link DatabaseConnectionAlertTemplate} once on a worker. The backend only ships the
 * PostgreSQL driver and may not reach the database network at all, so the check runs where
 * real executions run. Nothing is persisted: no alert, no execution history, no secret.
 */
@Service
public class DatabaseSecretProbeService {
    private static final String EVENT = "SECRET_DATABASE_TESTED";
    private static final String PROBE_NAME = "DB_SECRET connection test";
    private static final int TIMEOUT_SECONDS = 10;
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(45);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AlertTemplateDefinitionRepository templateRepository;
    private final AlertExecutionPreparationService preparationService;
    private final WorkerStatusService workerStatusService;
    private final AlertWorkerClient workerClient;
    private final ApplicationEventLogger eventLogger;

    public DatabaseSecretProbeService(AlertTemplateDefinitionRepository templateRepository, AlertExecutionPreparationService preparationService, WorkerStatusService workerStatusService, AlertWorkerClient workerClient, ApplicationEventLogger eventLogger) {
        this.templateRepository = templateRepository;
        this.preparationService = preparationService;
        this.workerStatusService = workerStatusService;
        this.workerClient = workerClient;
        this.eventLogger = eventLogger;
    }

    public DatabaseSecretTestResponse test(DatabaseCredentials credentials) {
        return test(credentials, null, null);
    }

    /** Probes credentials, tagging the audit event with the stored secret they belong to when there is one. */
    public DatabaseSecretTestResponse test(DatabaseCredentials credentials, Long secretId, String secretName) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (secretId != null)
            data.put("secretId", secretId);
        if (secretName != null)
            data.put("name", secretName);
        data.put("engine", credentials.engine().name());
        data.put("host", credentials.host());
        data.put("port", credentials.port());
        data.put("database", credentials.database());
        data.put("username", credentials.username());
        DatabaseSecretTestResponse response;
        try {
            response = probe(credentials);
        } catch (RuntimeException exception) {
            data.put("exceptionType", exception.getClass().getName());
            if (exception.getMessage() != null)
                data.put("exceptionMessage", exception.getMessage());
            eventLogger.failure(EVENT, data);
            throw exception;
        }
        data.put("connected", response.connected());
        if (response.failureReason() != null)
            data.put("failureReason", response.failureReason());
        if (response.workerName() != null)
            data.put("workerName", response.workerName());
        if (response.connected())
            eventLogger.success(EVENT, data);
        else
            eventLogger.failure(EVENT, data);
        return response;
    }

    private DatabaseSecretTestResponse probe(DatabaseCredentials credentials) {
        AlertTemplateDefinition template = templateRepository.findByTemplateKey(DatabaseConnectionAlertTemplate.class.getName())
                .orElseThrow(() -> new IllegalStateException("DatabaseConnectionAlertTemplate is not registered"));
        List<ResolvedAlertParameter> parameters = List.of(
                new ResolvedAlertParameter("credentials", DatabaseCredentials.class.getName(), credentials.toJson(), null, false,
                        AlertParameterSource.SECRET, null, null, null, false, null),
                new ResolvedAlertParameter("timeoutSeconds", "int", Integer.toString(TIMEOUT_SECONDS), null, false,
                        AlertParameterSource.TEXT, null, null, null, false, null));
        PreparedAlertExecution execution = preparationService.prepareAdHoc(template, PROBE_NAME, parameters);

        try (WorkerReservation reservation = workerStatusService.reserve(execution.requiredCapability())) {
            SelectedWorker worker = reservation.worker();
            ExecuteAlertRequest.Builder request = ExecuteAlertRequest.newBuilder()
                    .setExecutionId(UUID.randomUUID().toString())
                    .setAlertId(execution.alertId())
                    .setAlertName(execution.alertName())
                    .setTemplateClassName(execution.templateClassName())
                    .setSourceChecksum(execution.sourceChecksum())
                    .setState(execution.state());
            for (ResolvedAlertParameter parameter : execution.parameters()) {
                request.addParameters(AlertParameter.newBuilder()
                        .setName(parameter.name())
                        .setJavaType(parameter.javaType())
                        .setValue(parameter.value())
                        .setSource(parameter.source() == AlertParameterSource.SECRET
                                ? AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET
                                : AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_TEXT));
            }
            SynchronizeTemplateRequest source = SynchronizeTemplateRequest.newBuilder()
                    .setTemplateClassName(execution.templateClassName())
                    .setSourceChecksum(execution.sourceChecksum())
                    .setSource(execution.source())
                    .setTemplateKind(TemplateKind.TEMPLATE_KIND_ALERT)
                    .build();
            AlertExecutionResult result;
            try {
                result = workerClient.executeAlert(worker.endpoint(), request.build(), source, EXECUTION_TIMEOUT, token -> {
                    throw new IllegalStateException("Procedure invocations are not available while probing credentials");
                });
            } catch (WorkerTemplateSynchronizationException exception) {
                return failure("execution_error", exception.error().getMessage(), worker.status().getWorkerName());
            }
            return toResponse(result, worker.status().getWorkerName());
        }
    }

    private static DatabaseSecretTestResponse toResponse(AlertExecutionResult result, String workerName) {
        if (result.hasError())
            return failure("execution_error", result.getError().getMessage(), workerName);

        JsonNode message = result.getStatusMessageJson().isBlank() ? JSON.createObjectNode() : JSON.readTree(result.getStatusMessageJson());
        return new DatabaseSecretTestResponse(
                message.path("connected").asBoolean(false),
                text(message, "failureReason"), text(message, "failureMessage"), text(message, "sqlState"),
                text(message, "productName"), text(message, "productVersion"), text(message, "driverName"),
                number(message, "connectMs"), number(message, "totalLatencyMs"), workerName);
    }

    private static DatabaseSecretTestResponse failure(String reason, String message, String workerName) {
        return new DatabaseSecretTestResponse(false, reason, message == null || message.isBlank() ? null : message,
                null, null, null, null, null, null, workerName);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
