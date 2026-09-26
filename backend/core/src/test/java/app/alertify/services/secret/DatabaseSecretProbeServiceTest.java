package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.alerts.execution.AlertExecutionPreparationService;
import app.alertify.alerts.execution.PreparedAlertExecution;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.templates.DatabaseConnectionAlertTemplate;
import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.discovery.SelectedWorker;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.grpc.discovery.WorkerReservation;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.secret.api.DatabaseSecretTestResponse;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.WorkerStatusResponse;

@ExtendWith(MockitoExtension.class)
class DatabaseSecretProbeServiceTest {

    private static final String EVENT = "SECRET_DATABASE_TESTED";
    private static final String SECRET_HOST = "secret-host.example";
    private static final String SECRET_DATABASE = "secret-database";
    private static final String SECRET_USERNAME = "secret-username";
    private static final String SECRET_PASSWORD = "secret-password";

    @Mock private AlertTemplateDefinitionRepository templateRepository;
    @Mock private AlertExecutionPreparationService preparationService;
    @Mock private WorkerStatusService workerStatusService;
    @Mock private AlertWorkerClient workerClient;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private AlertTemplateDefinition template;
    @Mock private WorkerReservation reservation;

    @Test
    void auditsSuccessfulDraftWithoutCredentialMaterial() {
        arrangeProbe(result(true, null, 12, 18));

        DatabaseSecretTestResponse response = service().test(credentials());

        assertThat(response.connected()).isTrue();
        assertThat(response.failureReason()).isNull();
        assertThat(response.productName()).isEqualTo("PostgreSQL");
        Map<String, Object> data = successfulAuditData();
        assertThat(data).containsEntry("targetKind", "DRAFT").containsEntry("connected", true)
                .containsEntry("connectMs", 12L).containsEntry("totalLatencyMs", 18L)
                .containsEntry("workerName", "worker-standard")
                .doesNotContainKeys("secretId", "name");
        assertContainsNoCredentialMaterial(data);
    }

    @Test
    void auditsStoredFailureWithNormalizedReasonAndNoCredentialMaterial() {
        arrangeProbe(result(false, "auth_failed", 7, 9));

        DatabaseSecretTestResponse response = service().test(credentials(), 41L, "production.database");

        assertThat(response.connected()).isFalse();
        assertThat(response.failureReason()).isEqualTo("auth_failed");
        Map<String, Object> data = failedAuditData();
        assertThat(data).containsEntry("targetKind", "STORED").containsEntry("secretId", 41L)
                .containsEntry("name", "production.database").containsEntry("connected", false)
                .containsEntry("failureReason", "auth_failed");
        assertContainsNoCredentialMaterial(data);
    }

    @Test
    void normalizesUnexpectedWorkerFailureReason() {
        arrangeProbe(result(false, SECRET_PASSWORD, 3, 5));

        DatabaseSecretTestResponse response = service().test(credentials());

        assertThat(response.failureReason()).isEqualTo("unknown");
        Map<String, Object> data = failedAuditData();
        assertThat(data).containsEntry("failureReason", "unknown");
        assertContainsNoCredentialMaterial(data);
    }

    @Test
    void auditsExceptionTypeWithoutExceptionMessageOrCredentialMaterial() {
        when(templateRepository.findByTemplateKey(DatabaseConnectionAlertTemplate.class.getName()))
                .thenThrow(new IllegalStateException("Connection failed for " + SECRET_PASSWORD));

        assertThatThrownBy(() -> service().test(credentials()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SECRET_PASSWORD);

        Map<String, Object> data = failedAuditData();
        assertThat(data).containsEntry("targetKind", "DRAFT").containsEntry("connected", false)
                .containsEntry("failureReason", "execution_error")
                .containsEntry("exceptionType", IllegalStateException.class.getName())
                .doesNotContainKey("exceptionMessage");
        assertContainsNoCredentialMaterial(data);
    }

    private void arrangeProbe(AlertExecutionResult result) {
        WorkerEndpoint endpoint = new WorkerEndpoint("10.0.0.7", 9090);
        WorkerStatusResponse status = WorkerStatusResponse.newBuilder().setWorkerName("worker-standard").build();
        when(templateRepository.findByTemplateKey(DatabaseConnectionAlertTemplate.class.getName())).thenReturn(Optional.of(template));
        when(preparationService.prepareAdHoc(eq(template), eq("DB_SECRET connection test"), anyList()))
                .thenAnswer(invocation -> new PreparedAlertExecution(
                        0, "DB_SECRET connection test", DatabaseConnectionAlertTemplate.class.getName(),
                        WorkerCapability.STANDARD, "checksum", "source", "", invocation.getArgument(2)
                ));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(reservation.worker()).thenReturn(new SelectedWorker(endpoint, status));
        when(workerClient.executeAlert(eq(endpoint), any(ExecuteAlertRequest.class), any(SynchronizeTemplateRequest.class), any(Duration.class), any())).thenReturn(result);
    }

    private static AlertExecutionResult result(boolean connected, String failureReason, long connectMs, long totalLatencyMs) {
        String reason = failureReason == null ? "null" : '"' + failureReason + '"';
        String json = "{\"connected\":" + connected + ",\"failureReason\":" + reason
                + ",\"failureMessage\":\"Connection failed for " + SECRET_PASSWORD + "\""
                + ",\"host\":\"" + SECRET_HOST + "\",\"database\":\"" + SECRET_DATABASE + "\""
                + ",\"username\":\"" + SECRET_USERNAME + "\",\"sqlState\":\"28000\""
                + ",\"productName\":\"PostgreSQL\",\"productVersion\":\"18.4\",\"driverName\":\"driver\""
                + ",\"connectMs\":" + connectMs + ",\"totalLatencyMs\":" + totalLatencyMs + '}';
        return AlertExecutionResult.newBuilder().setStatusMessageJson(json).build();
    }

    private DatabaseSecretProbeService service() {
        return new DatabaseSecretProbeService(templateRepository, preparationService, workerStatusService, workerClient, eventLogger);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Map<String, Object> successfulAuditData() {
        ArgumentCaptor<Map<String, ?>> data = ArgumentCaptor.forClass((Class) Map.class);
        verify(eventLogger).success(eq(EVENT), data.capture());
        return (Map) data.getValue();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Map<String, Object> failedAuditData() {
        ArgumentCaptor<Map<String, ?>> data = ArgumentCaptor.forClass((Class) Map.class);
        verify(eventLogger).failure(eq(EVENT), data.capture());
        return (Map) data.getValue();
    }

    private static void assertContainsNoCredentialMaterial(Map<String, ?> data) {
        assertThat(data).doesNotContainKeys("engine", "host", "port", "database", "username", "password", "failureMessage", "exceptionMessage", "sqlState", "productName", "productVersion", "driverName");
        assertThat(data.toString()).doesNotContain(SECRET_HOST, SECRET_DATABASE, SECRET_USERNAME, SECRET_PASSWORD);
    }

    private static DatabaseCredentials credentials() {
        return new DatabaseCredentials(DatabaseEngine.POSTGRESQL, SECRET_HOST, 5432, SECRET_DATABASE, SECRET_USERNAME, SECRET_PASSWORD, null);
    }
}
