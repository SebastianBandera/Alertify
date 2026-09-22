package app.alertify.hooks.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.alerts.model.Alert;
import app.alertify.api.error.InvalidHookImportException;
import app.alertify.hooks.api.HookCreateRequest;
import app.alertify.hooks.api.HookImportError;
import app.alertify.hooks.api.HookImportResult;
import app.alertify.hooks.api.HookResponse;
import app.alertify.hooks.api.HookTargetRequest;
import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.model.Procedure;
import app.alertify.pipes.model.Pipe;

/**
 * CSV import/export of hooks. Alerts, procedures and token secrets must already
 * exist; rows referencing missing resources are skipped and reported while the
 * remaining rows are still applied.
 */
@Service
public class HookCsvService {
    private static final long MAX_IMPORT_FILE_SIZE = 10L * 1024 * 1024;
    private static final String NOT_FOUND_SUFFIX = "' was not found";

    private final HookRepository hookRepository;
    private final AlertRepository alertRepository;
    private final ProcedureRepository procedureRepository;
    private final PipeRepository pipeRepository;
    private final ApplicationSecretRepository secretRepository;
    private final HookManagementService managementService;
    private final HookCsvCodec codec;
    private final ApplicationEventLogger eventLogger;

    public HookCsvService(HookRepository hookRepository, AlertRepository alertRepository,
            ProcedureRepository procedureRepository, PipeRepository pipeRepository,
            ApplicationSecretRepository secretRepository,
            HookManagementService managementService, HookCsvCodec codec, ApplicationEventLogger eventLogger) {
        this.hookRepository = hookRepository;
        this.alertRepository = alertRepository;
        this.procedureRepository = procedureRepository;
        this.pipeRepository = pipeRepository;
        this.secretRepository = secretRepository;
        this.managementService = managementService;
        this.codec = codec;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        List<Hook> hooks = hookRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
        eventLogger.success("HOOK_EXPORT", Map.of("count", hooks.size()));
        return codec.write(hooks);
    }

    @Transactional
    public HookImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new InvalidHookImportException("A non-empty CSV file is required");

        if (file.getSize() > MAX_IMPORT_FILE_SIZE) throw new InvalidHookImportException("CSV file exceeds the 10 MB limit");

        HookCsvCodec.ReadResult read;

        try { read = codec.read(file.getBytes()); }
        catch (IOException exception) { throw new InvalidHookImportException("Unable to read the CSV file", exception); }

        Map<String, Alert> alerts = byName(alertRepository.findAll(), Alert::getName);
        Map<String, Procedure> procedures = byName(procedureRepository.findAll(), Procedure::getName);
        Map<String, Pipe> pipes = byName(pipeRepository.findAll(), Pipe::getName);
        Map<String, ApplicationSecret> secrets = byName(secretRepository.findAll(), ApplicationSecret::getName);
        List<Hook> existing = hookRepository.findAll(Sort.by("name"));
        Map<String, Hook> hooks = byName(existing, Hook::getName);
        Set<UUID> publicIds = existing.stream().map(Hook::getPublicId).collect(Collectors.toSet());
        List<HookImportError> errors = new ArrayList<>(read.errors());

        // Resolve every reference before touching the management service, so a
        // bad row never marks the shared transaction rollback-only.
        List<Resolved> resolved = new ArrayList<>();
        for (HookCsvCodec.ImportRow row : read.rows()) {
            try {
                resolved.add(resolve(row, hooks.get(key(row.name())), alerts, procedures, pipes, secrets, publicIds));
            } catch (HookCsvCodec.RowException exception) {
                errors.add(new HookImportError(row.rowNumber(), row.name(), exception.getMessage()));
            }
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Resolved entry : resolved) {
            HookCsvCodec.ImportRow row = entry.row();
            if (entry.existing() == null) {
                HookResponse response = managementService.create(new HookCreateRequest(row.name(), row.description(), row.mode(),
                        entry.tokenSecretId(), row.maxConcurrentInvocations(), row.rateLimitCount(), window(row), entry.targets()));
                if (row.publicId() != null || row.enabled()) {
                    Hook hook = hookRepository.findById(response.id()).orElseThrow();
                    if (row.publicId() != null) hook.restorePublicId(row.publicId());

                    managementService.update(hook.getId(), updateRequest(hook.getVersion(), row, entry));
                }
                created++;
                continue;
            }
            if (unchanged(entry.existing(), row, entry)) {
                unchanged++;
                continue;
            }
            managementService.update(entry.existing().getId(), updateRequest(entry.existing().getVersion(), row, entry));
            updated++;
        }
        int total = read.rows().size() + read.errors().size();
        eventLogger.successAfterCommit("HOOK_IMPORT", Map.of("total", total, "created", created, "updated", updated, "unchanged", unchanged, "skipped", errors.size()));

        return new HookImportResult(total, created, updated, unchanged, errors.size(), List.copyOf(errors));
    }

    private Resolved resolve(HookCsvCodec.ImportRow row, Hook existing, Map<String, Alert> alerts,
            Map<String, Procedure> procedures, Map<String, Pipe> pipes,
            Map<String, ApplicationSecret> secrets, Set<UUID> publicIds) {
        Long tokenSecretId = null;
        if (row.tokenSecret() != null) {
            ApplicationSecret secret = secrets.get(key(row.tokenSecret()));
            if (secret == null) throw new HookCsvCodec.RowException("secret '" + row.tokenSecret() + NOT_FOUND_SUFFIX);

            if (secret.getValueType() != SecretValueType.STRING)
                throw new HookCsvCodec.RowException("secret '" + row.tokenSecret() + "' must be a STRING secret");

            tokenSecretId = secret.getId();
        }
        if (existing == null && row.publicId() != null && publicIds.contains(row.publicId()))
            throw new HookCsvCodec.RowException("publicId '" + row.publicId() + "' is already used by another hook");

        if (row.enabled() && row.targets().isEmpty())
            throw new HookCsvCodec.RowException("an enabled hook requires at least one target");

        List<HookTargetRequest> targets = new ArrayList<>();
        for (HookCsvCodec.ImportTarget target : row.targets()) {
            Long resourceId;
            resourceId = switch (target.type()) {
                case ALERT -> {
                    Alert alert = alerts.get(key(target.name()));
                    if (alert == null) throw new HookCsvCodec.RowException("alert '" + target.name() + NOT_FOUND_SUFFIX);

                    yield alert.getId();
                }
                case PROCEDURE -> {
                    Procedure procedure = procedures.get(key(target.name()));
                    if (procedure == null) throw new HookCsvCodec.RowException("procedure '" + target.name() + NOT_FOUND_SUFFIX);

                    yield procedure.getId();
                }
                case PIPE -> {
                    Pipe pipe = pipes.get(key(target.name()));
                    if (pipe == null) throw new HookCsvCodec.RowException("Pipe '" + target.name() + NOT_FOUND_SUFFIX);

                    yield pipe.getId();
                }
            };
            targets.add(new HookTargetRequest(target.type(), resourceId, target.continueOn(), Duration.ofMillis(target.busyWaitTimeoutMillis())));
        }
        if (existing == null && row.publicId() != null) publicIds.add(row.publicId());

        return new Resolved(row, existing, tokenSecretId, List.copyOf(targets));
    }

    private static HookUpdateRequest updateRequest(long version, HookCsvCodec.ImportRow row, Resolved entry) {
        return new HookUpdateRequest(version, row.name(), row.description(), row.enabled(), row.mode(), entry.tokenSecretId(),
                row.maxConcurrentInvocations(), row.rateLimitCount(), window(row), entry.targets());
    }

    private static Duration window(HookCsvCodec.ImportRow row) {
        return row.rateLimitWindowSeconds() == null ? null : Duration.ofSeconds(row.rateLimitWindowSeconds());
    }

    private static boolean unchanged(Hook hook, HookCsvCodec.ImportRow row, Resolved entry) {
        Long currentSecret = hook.getTokenSecret() == null ? null : hook.getTokenSecret().getId();
        if (!hook.getName().equals(row.name()) || !Objects.equals(hook.getDescription(), row.description())
                || hook.isEnabled() != row.enabled() || hook.getMode() != row.mode()
                || !Objects.equals(currentSecret, entry.tokenSecretId())
                || !Objects.equals(hook.getMaxConcurrentInvocations(), row.maxConcurrentInvocations())
                || !Objects.equals(hook.getRateLimitCount(), row.rateLimitCount())
                || !Objects.equals(hook.getRateLimitWindowSeconds(), row.rateLimitWindowSeconds())) return false;

        List<String> current = hook.getTargets().stream().map(HookCsvService::fingerprint).toList();
        List<String> requested = entry.targets().stream().map(HookCsvService::fingerprint).toList();
        return current.equals(requested);
    }

    private static String fingerprint(HookTarget target) {
        Long resourceId = switch (target.getTargetType()) {
            case ALERT -> target.getAlert().getId();
            case PROCEDURE -> target.getProcedure().getId();
            case PIPE -> target.getPipe().getId();
        };
        return target.getTargetType() + " " + resourceId + " " + new TreeSet<>(target.getContinueOn()) + " " + target.getBusyWaitTimeoutMillis();
    }

    private static String fingerprint(HookTargetRequest target) {
        Set<String> outcomes = target.continueOn().stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        return target.type() + " " + target.resourceId() + " " + outcomes + " " + target.busyWaitTimeout().toMillis();
    }

    private static <T> Map<String, T> byName(List<T> values, Function<T, String> name) {
        return values.stream().collect(Collectors.toMap(value -> key(name.apply(value)), Function.identity(),
                (left, _) -> left, LinkedHashMap::new));
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }

    private record Resolved(HookCsvCodec.ImportRow row, Hook existing, Long tokenSecretId, List<HookTargetRequest> targets) { }
}
