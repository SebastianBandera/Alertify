package app.alertify.alerts.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.protobuf.Timestamp;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.discovery.SelectedWorker;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.grpc.discovery.WorkerReservation;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.Procedure;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureInvocationRegistry;
import app.alertify.procedures.execution.ProcedureInvocationTokenService;
import app.alertify.system.SystemStatusTickerPublisher;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.WorkerExecutionStatus;
import app.alertify.worker.grpc.WorkerStatusResponse;

@ExtendWith(MockitoExtension.class)
class AlertExecutionOrchestratorTest {

    private static final WorkerEndpoint ENDPOINT = new WorkerEndpoint("10.0.0.2", 9090);
    private static final String CHECKSUM = "a".repeat(64);
    private static final String WORKER_INSTANCE_ID = "9e79e59b-1e07-43e8-b923-547747b04132";

    @Mock private AlertExecutionPreparationService preparationService;
    @Mock private AlertExecutionPersistenceService persistenceService;
    @Mock private WorkerStatusService workerStatusService;
    @Mock private AlertWorkerClient workerClient;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private WorkerReservation reservation;
    @Mock private ProcedureInvocationTokenService procedureTokenService;
    @Mock private ProcedureInvocationRegistry procedureInvocationRegistry;
    @Mock private ProcedureExecutionOrchestrator procedureExecutionOrchestrator;
    @Mock private CronQuietHoursService quietHoursService;
    @Mock private MaintenanceModeService maintenanceModeService;
    @Mock private SystemStatusTickerPublisher statusTickerPublisher;

    private AlertExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // Lenient: the quiet-hours short-circuit test never reaches worker
        // reservation, so this shared stub is unused there by design.
        org.mockito.Mockito.lenient().when(reservation.worker()).thenReturn(new SelectedWorker(
                ENDPOINT,
                WorkerStatusResponse.newBuilder()
                        .setWorkerName("standard-worker")
                        .setWorkerInstanceId(WORKER_INSTANCE_ID)
                        .addCapabilities(WorkerCapability.STANDARD.name())
                        .build()
        ));
        orchestrator = new AlertExecutionOrchestrator(
                preparationService, persistenceService, workerStatusService, workerClient,
                properties(), eventLogger, procedureTokenService, procedureInvocationRegistry, procedureExecutionOrchestrator,
                quietHoursService, maintenanceModeService, statusTickerPublisher
        );
    }

    @AfterEach
    void closeOrchestrator() {
        orchestrator.close();
    }

    @Test
    void suppliesTemplateSourceOnTheSameExecutionStream() {
        PreparedAlertExecution prepared = prepared();
        AlertExecutionResult result = successfulResult();
        when(preparationService.prepare(7L, false)).thenReturn(Optional.of(prepared));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any())).thenReturn(result);

        orchestrator.trigger(7L, "Sample alert", false);

        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), eq(result)
        );
        ArgumentCaptor<ExecuteAlertRequest> executionRequest =
                ArgumentCaptor.forClass(ExecuteAlertRequest.class);
        ArgumentCaptor<SynchronizeTemplateRequest> synchronizationRequest = ArgumentCaptor.forClass(SynchronizeTemplateRequest.class);
        verify(workerClient).executeAlert(eq(ENDPOINT), executionRequest.capture(), synchronizationRequest.capture(), any(Duration.class), any());
        assertThat(executionRequest.getValue()).satisfies(request -> {
            assertThat(request.getAlertId()).isEqualTo(7L);
            assertThat(request.getAlertName()).isEqualTo("Sample alert");
            assertThat(request.getTemplateClassName()).isEqualTo("dynamic.SampleAlert");
            assertThat(request.getSourceChecksum()).isEqualTo(CHECKSUM);
            assertThat(request.getState()).isEqualTo("previous-state");
            assertThat(request.getParametersCount()).isEqualTo(1);
            assertThat(request.getParameters(0).getName()).isEqualTo("endpoint");
            assertThat(request.getParameters(0).getValue()).isEqualTo("google");
            assertThat(request.getParameters(0).getSource())
                    .isEqualTo(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET);
            assertThat(request.getParameters(0).getWritable()).isTrue();
            assertThat(request.getParameters(0).getSecretId()).isEqualTo(73L);
            assertThat(request.getParameters(0).getConfigurationId()).isZero();
        });
        assertThat(synchronizationRequest.getValue().getTemplateClassName())
                .isEqualTo("dynamic.SampleAlert");
        assertThat(synchronizationRequest.getValue().getSource()).isEqualTo("source");
        verify(reservation).close();
    }

    @Test
    void transportsAReadOnlySecretSourceWithoutItsTargetId() {
        PreparedAlertExecution prepared = new PreparedAlertExecution(
                7L, "Sample alert", "dynamic.SampleAlert", WorkerCapability.STANDARD,
                CHECKSUM, "source", "previous-state",
                List.of(new ResolvedAlertParameter(
                        "token", String.class.getName(), "secret-value", false,
                        AlertParameterSource.SECRET, null, 73L, false
                ))
        );
        when(preparationService.prepare(7L, false)).thenReturn(Optional.of(prepared));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any())).thenReturn(successfulResult());

        orchestrator.trigger(7L, "Sample alert", false);

        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), any(AlertExecutionResult.class)
        );
        ArgumentCaptor<ExecuteAlertRequest> request = ArgumentCaptor.forClass(ExecuteAlertRequest.class);
        verify(workerClient).executeAlert(eq(ENDPOINT), request.capture(), any(), any(Duration.class), any());
        assertThat(request.getValue().getParameters(0).getSource())
                .isEqualTo(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET);
        assertThat(request.getValue().getParameters(0).getSecretId()).isZero();
        assertThat(request.getValue().getParameters(0).getConfigurationId()).isZero();
        assertThat(request.getValue().getParameters(0).getWritable()).isFalse();
    }

    @Test
    void transportsAProcedureAsANonNullSignedHandle() {
        PreparedAlertExecution prepared = new PreparedAlertExecution(
                7L, "Sample alert", "dynamic.SampleAlert", WorkerCapability.STANDARD,
                CHECKSUM, "source", "previous-state",
                List.of(new ResolvedAlertParameter(
                        "procedure", Procedure.class.getName(), null, true,
                        AlertParameterSource.PROCEDURE, null, null, 42L, false
                ))
        );
        when(preparationService.prepare(7L, false)).thenReturn(Optional.of(prepared));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(procedureTokenService.issue(eq(42L), any(UUID.class), any(UUID.class), any(),
                eq(1), any(Instant.class))).thenReturn("signed-token");
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any())).thenReturn(successfulResult());

        orchestrator.trigger(7L, "Sample alert", false);

        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), any(AlertExecutionResult.class)
        );
        ArgumentCaptor<ExecuteAlertRequest> request = ArgumentCaptor.forClass(ExecuteAlertRequest.class);
        verify(workerClient).executeAlert(eq(ENDPOINT), request.capture(), any(), any(Duration.class), any());
        assertThat(request.getValue().getParameters(0).getSource())
                .isEqualTo(AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_PROCEDURE);
        assertThat(request.getValue().getParameters(0).getNullValue()).isFalse();
        assertThat(request.getValue().getParameters(0).getProcedureId()).isEqualTo(42L);
        assertThat(request.getValue().getParameters(0).getInvocationToken()).isEqualTo("signed-token");
    }

    @Test
    void skipsANewTriggerWhileTheSameAlertIsAlreadyRunningByDefault() throws Exception {
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        when(preparationService.prepare(7L, false)).thenReturn(Optional.of(prepared()));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    executionStarted.countDown();
                    if (!releaseExecution.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test did not release the worker execution");

                    return successfulResult();
                });

        orchestrator.trigger(7L, "Sample alert", false);
        assertThat(executionStarted.await(5, TimeUnit.SECONDS)).isTrue();

        orchestrator.trigger(7L, "Sample alert", false);

        verify(eventLogger).failure(eq("ALERT_EXECUTION_SKIPPED"), any());
        releaseExecution.countDown();
        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), any(AlertExecutionResult.class)
        );
        verify(workerClient).executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any());
    }

    @Test
    void cronTriggerDoesNothingDuringQuietHoursAndLogsNothing() {
        when(quietHoursService.isQuietNow()).thenReturn(true);

        orchestrator.trigger(7L, "Sample alert", false);

        org.mockito.Mockito.verifyNoInteractions(preparationService, persistenceService, workerStatusService, workerClient);
        org.mockito.Mockito.verifyNoInteractions(eventLogger);
    }

    @Test
    void manualTriggerIgnoresQuietHours() {
        // No stubbing of quietHoursService: the MANUAL path never consults it
        // at all (only the CRON-only 3-arg trigger() overload does), so this
        // test proves manual execution is unaffected without needing to.
        when(preparationService.prepare(7L, true)).thenReturn(Optional.of(prepared()));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any())).thenReturn(successfulResult());

        boolean accepted = orchestrator.trigger(
                7L, "Sample alert", false, AlertExecutionTrigger.MANUAL, "sebastian"
        );

        assertThat(accepted).isTrue();
        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), any(AlertExecutionResult.class)
        );
    }

    @Test
    void manualTriggerRunsDisabledAlertsAndRecordsTheOperator() {
        when(preparationService.prepare(7L, true)).thenReturn(Optional.of(prepared()));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any())).thenReturn(successfulResult());

        boolean accepted = orchestrator.trigger(
                7L, "Sample alert", false, AlertExecutionTrigger.MANUAL, "sebastian"
        );

        assertThat(accepted).isTrue();
        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), any(AlertExecutionResult.class)
        );
        ArgumentCaptor<java.util.Map<String, Object>> triggered = ArgumentCaptor.captor();
        verify(eventLogger).success(eq("ALERT_EXECUTION_TRIGGERED"), triggered.capture());
        assertThat(triggered.getValue())
                .containsEntry("trigger", "MANUAL")
                .containsEntry("triggeredBy", "sebastian")
                .containsEntry("alertId", 7L);
        // A disabled alert must reach the preparation service with the flag set.
        verify(preparationService).prepare(7L, true);
    }

    @Test
    void manualTriggerIsRejectedWhileTheAlertIsRunningWithoutConcurrency() throws Exception {
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        when(preparationService.prepare(7L, false)).thenReturn(Optional.of(prepared()));
        when(workerStatusService.reserve(WorkerCapability.STANDARD)).thenReturn(reservation);
        when(workerClient.executeAlert(eq(ENDPOINT), any(), any(), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    executionStarted.countDown();
                    if (!releaseExecution.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test did not release the worker execution");

                    return successfulResult();
                });

        orchestrator.trigger(7L, "Sample alert", false);
        assertThat(executionStarted.await(5, TimeUnit.SECONDS)).isTrue();

        boolean accepted = orchestrator.trigger(
                7L, "Sample alert", false, AlertExecutionTrigger.MANUAL, "sebastian"
        );

        assertThat(accepted).isFalse();
        ArgumentCaptor<java.util.Map<String, Object>> skipped = ArgumentCaptor.captor();
        verify(eventLogger).failure(eq("ALERT_EXECUTION_SKIPPED"), skipped.capture());
        assertThat(skipped.getValue())
                .containsEntry("trigger", "MANUAL")
                .containsEntry("triggeredBy", "sebastian")
                .containsEntry("reason", "ALREADY_RUNNING");
        releaseExecution.countDown();
        verify(persistenceService, org.mockito.Mockito.timeout(5000)).persistWorkerResult(
                eq(7L), any(UUID.class), eq(ENDPOINT), any(AlertExecutionResult.class)
        );
    }

    private static PreparedAlertExecution prepared() {
        return new PreparedAlertExecution(
                7L, "Sample alert", "dynamic.SampleAlert", WorkerCapability.STANDARD,
                CHECKSUM, "source", "previous-state",
                List.of(new ResolvedAlertParameter(
                        "endpoint", String.class.getName(), "google", false,
                        AlertParameterSource.SECRET, null, 73L, true
                ))
        );
    }

    private static AlertExecutionResult successfulResult() {
        Instant startedAt = Instant.parse("2026-08-30T12:00:00Z");
        return AlertExecutionResult.newBuilder()
                .setStatus(WorkerExecutionStatus.WORKER_EXECUTION_STATUS_SUCCESS)
                .setStartedAt(timestamp(startedAt))
                .setWorkStartedAt(timestamp(startedAt.plusMillis(25)))
                .setFinishedAt(timestamp(startedAt.plusSeconds(1)))
                .setStatusMessageJson("{}")
                .setState("updated-state")
                .setWorkerName("standard-worker")
                .setWorkerInstanceId(WORKER_INSTANCE_ID)
                .build();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder()
                .setSeconds(value.getEpochSecond())
                .setNanos(value.getNano())
                .build();
    }

    private static WorkerGrpcProperties properties() {
        return new WorkerGrpcProperties(
                "worker", 9090,
                new WorkerGrpcProperties.Tls(false, null, null, null, null),
                new WorkerGrpcProperties.Discovery(
                        true, Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(2)
                ),
                new WorkerGrpcProperties.Execution(
                        Duration.ofMinutes(30), Path.of("src")
                )
        );
    }
}
