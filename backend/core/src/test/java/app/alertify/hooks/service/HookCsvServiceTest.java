package app.alertify.hooks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.hooks.api.HookCreateRequest;
import app.alertify.hooks.api.HookImportError;
import app.alertify.hooks.api.HookImportResult;
import app.alertify.hooks.api.HookResponse;
import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HookCsvServiceTest {
    private static final String HEADER = "publicId,name,description,enabled,mode,tokenSecret,maxConcurrentInvocations,rateLimitCount,rateLimitWindowSeconds,targets";
    private static final String PUBLIC_ID = "11111111-2222-3333-4444-555555555555";
    private static final String DISK_TARGET = "\"[{\"\"type\"\":\"\"ALERT\"\",\"\"name\"\":\"\"Disk usage\"\",\"\"continueOn\"\":[\"\"SUCCESS\"\"],\"\"busyWaitTimeoutMillis\"\":1000}]\"";

    @Mock private HookRepository hookRepository;
    @Mock private AlertRepository alertRepository;
    @Mock private ProcedureRepository procedureRepository;
    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private HookManagementService managementService;
    @Mock private ApplicationEventLogger eventLogger;

    private final Alert alert = alert("Disk usage", 9L);

    @Test
    void skipsRowsWithMissingReferencesAndImportsTheRest() {
        stubCatalog(List.of());
        when(managementService.create(any())).thenReturn(response(20L));

        HookImportResult result = service().importCsv(file(
                ",broken,,false,PARALLEL,,,,,\"[{\"\"type\"\":\"\"ALERT\"\",\"\"name\"\":\"\"Missing\"\"}]\"",
                ",no-secret,,false,PARALLEL,MISSING_SECRET,,,,[]",
                ",ok,Nightly run,false,SEQUENTIAL,,2,,," + DISK_TARGET));

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.errors()).containsExactly(
                new HookImportError(2, "broken", "alert 'Missing' was not found"),
                new HookImportError(3, "no-secret", "secret 'MISSING_SECRET' was not found"));
        ArgumentCaptor<HookCreateRequest> captor = ArgumentCaptor.forClass(HookCreateRequest.class);
        verify(managementService).create(captor.capture());
        HookCreateRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo("ok");
        assertThat(request.description()).isEqualTo("Nightly run");
        assertThat(request.mode()).isEqualTo(HookMode.SEQUENTIAL);
        assertThat(request.maxConcurrentInvocations()).isEqualTo(2);
        assertThat(request.targets()).singleElement().satisfies(target -> {
            assertThat(target.type()).isEqualTo(HookTargetType.ALERT);
            assertThat(target.resourceId()).isEqualTo(9L);
            assertThat(target.continueOn()).containsExactly(HookOutcome.SUCCESS);
            assertThat(target.busyWaitTimeout()).isEqualTo(Duration.ofMillis(1000));
        });
        verify(managementService, never()).update(anyLong(), any());
    }

    @Test
    void restoresPublicIdAndEnablesNewHooks() {
        stubCatalog(List.of());
        Hook created = hook("ok", 20L, UUID.randomUUID());
        when(managementService.create(any())).thenReturn(response(20L));
        when(hookRepository.findById(20L)).thenReturn(Optional.of(created));

        HookImportResult result = service().importCsv(file(PUBLIC_ID + ",ok,,true,PARALLEL,,,,," + DISK_TARGET));

        assertThat(result).isEqualTo(new HookImportResult(1, 1, 0, 0, 0, List.of()));
        assertThat(created.getPublicId()).isEqualTo(UUID.fromString(PUBLIC_ID));
        ArgumentCaptor<HookUpdateRequest> captor = ArgumentCaptor.forClass(HookUpdateRequest.class);
        verify(managementService).update(eq(20L), captor.capture());
        assertThat(captor.getValue().enabled()).isTrue();
        assertThat(captor.getValue().version()).isZero();
    }

    @Test
    void rejectsAPublicIdAlreadyUsedByAnotherHook() {
        stubCatalog(List.of(hook("other", 5L, UUID.fromString(PUBLIC_ID))));

        HookImportResult result = service().importCsv(file(PUBLIC_ID + ",ok,,false,PARALLEL,,,,,[]"));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.message()).contains("already used"));
        verify(managementService, never()).create(any());
    }

    @Test
    void rejectsANonStringTokenSecret() {
        ApplicationSecret secret = new ApplicationSecret("BINARY_SECRET", null, SecretValueType.BINARY,
                new byte[0], new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), true);
        ReflectionTestUtils.setField(secret, "id", 3L);
        stubCatalog(List.of());
        when(secretRepository.findAll()).thenReturn(List.of(secret));

        HookImportResult result = service().importCsv(file(",ok,,false,PARALLEL,BINARY_SECRET,,,,[]"));

        assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.message()).contains("STRING"));
        verify(managementService, never()).create(any());
    }

    @Test
    void reportsUnchangedWhenTheRowMatchesTheStoredHook() {
        Hook existing = hook("ok", 5L, UUID.fromString(PUBLIC_ID));
        existing.update("ok", null, true, HookMode.PARALLEL, null, null, null, null);
        existing.replaceTargets(List.of(HookTarget.alert(existing, alert, 0, List.of("SUCCESS"), 1000L)));
        stubCatalog(List.of(existing));

        HookImportResult result = service().importCsv(file("," + "ok,,true,PARALLEL,,,,," + DISK_TARGET));

        assertThat(result).isEqualTo(new HookImportResult(1, 0, 0, 1, 0, List.of()));
        verify(managementService, never()).create(any());
        verify(managementService, never()).update(anyLong(), any());
    }

    @Test
    void updatesExistingHooksWhenTheRowDiffers() {
        Hook existing = hook("ok", 5L, UUID.fromString(PUBLIC_ID));
        stubCatalog(List.of(existing));

        HookImportResult result = service().importCsv(file(",ok,Changed,false,PARALLEL,,,,,[]"));

        assertThat(result).isEqualTo(new HookImportResult(1, 0, 1, 0, 0, List.of()));
        ArgumentCaptor<HookUpdateRequest> captor = ArgumentCaptor.forClass(HookUpdateRequest.class);
        verify(managementService).update(eq(5L), captor.capture());
        assertThat(captor.getValue().description()).isEqualTo("Changed");
        assertThat(existing.getPublicId()).isEqualTo(UUID.fromString(PUBLIC_ID));
    }

    private HookCsvService service() {
        return new HookCsvService(hookRepository, alertRepository, procedureRepository, secretRepository,
                managementService, new HookCsvCodec(JsonMapper.builder().build()), eventLogger);
    }

    private void stubCatalog(List<Hook> hooks) {
        when(alertRepository.findAll()).thenReturn(List.of(alert));
        when(procedureRepository.findAll()).thenReturn(List.of());
        when(secretRepository.findAll()).thenReturn(List.of());
        when(hookRepository.findAll(any(Sort.class))).thenReturn(hooks);
    }

    private static Hook hook(String name, long id, UUID publicId) {
        Hook hook = new Hook(name, null, HookMode.PARALLEL, null, null, null, null);
        ReflectionTestUtils.setField(hook, "id", id);
        ReflectionTestUtils.setField(hook, "publicId", publicId);
        return hook;
    }

    private static Alert alert(String name, long id) {
        Alert alert = new Alert(new AlertTemplateDefinition("template.A", "name", "description", "source.java", WorkerCapability.STANDARD),
                name, null, "0 0 * * * *", true);
        ReflectionTestUtils.setField(alert, "id", id);
        return alert;
    }

    private static HookResponse response(long id) {
        return new HookResponse(id, 0, UUID.randomUUID(), "ok", null, false, HookMode.PARALLEL, null, null, null, null, null, List.of(), null, null);
    }

    private static MockMultipartFile file(String... rows) {
        StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\r\n");
        for (String row : rows)
            csv.append(row).append("\r\n");

        return new MockMultipartFile("file", "alertify-hooks.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
