package app.alertify.pipes.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.alerts.model.Alert;
import app.alertify.api.error.InvalidPipeImportException;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.api.PipeBindingRequest;
import app.alertify.pipes.api.PipeCreateRequest;
import app.alertify.pipes.api.PipeImportResult;
import app.alertify.pipes.api.PipeStepRequest;
import app.alertify.pipes.api.PipeUpdateRequest;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepType;
import app.alertify.procedures.model.Procedure;

@Service
public class PipeCsvService {
    private static final long MAX_IMPORT_FILE_SIZE = 10L * 1024 * 1024;
    private final PipeRepository pipeRepository;
    private final AlertRepository alertRepository;
    private final ProcedureRepository procedureRepository;
    private final PipeManagementService managementService;
    private final PipeCsvCodec codec;
    private final ApplicationEventLogger eventLogger;

    public PipeCsvService(PipeRepository pipeRepository, AlertRepository alertRepository, ProcedureRepository procedureRepository, PipeManagementService managementService, PipeCsvCodec codec, ApplicationEventLogger eventLogger) {
        this.pipeRepository = pipeRepository;
        this.alertRepository = alertRepository;
        this.procedureRepository = procedureRepository;
        this.managementService = managementService;
        this.codec = codec;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        List<Pipe> pipes = pipeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                .map(pipe -> pipeRepository.findDetailedById(pipe.getId()).orElseThrow()).toList();
        eventLogger.success("PIPE_EXPORT", Map.of("count", pipes.size()));
        return codec.write(pipes);
    }

    @Transactional
    public PipeImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new InvalidPipeImportException("A non-empty CSV file is required");
        if (file.getSize() > MAX_IMPORT_FILE_SIZE)
            throw new InvalidPipeImportException("CSV file exceeds the 10 MB limit");

        List<PipeCsvCodec.ImportRow> rows;
        try { rows = codec.read(file.getBytes()); }
        catch (IOException exception) { throw new InvalidPipeImportException("Unable to read the CSV file", exception); }

        Map<String, Alert> alerts = byName(alertRepository.findAll(), Alert::getName);
        Map<String, Procedure> procedures = byName(procedureRepository.findAll(), Procedure::getName);
        Map<String, Pipe> pipes = byName(pipeRepository.findAll(), Pipe::getName);
        List<Resolved> resolved = rows.stream().map(row -> resolve(row, alerts, procedures, pipes.get(key(row.name())))).toList();

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Resolved entry : resolved) {
            PipeCsvCodec.ImportRow row = entry.row();
            if (entry.existing() == null) {
                managementService.create(new PipeCreateRequest(row.name(), row.description(), row.enabled(), row.allowConcurrentExecutions(), entry.steps()));
                created++;
            } else if (unchanged(entry.existing(), row, entry.steps())) {
                unchanged++;
            } else {
                managementService.update(entry.existing().getId(), new PipeUpdateRequest(entry.existing().getVersion(), row.name(), row.description(), row.enabled(), row.allowConcurrentExecutions(), entry.steps()));
                updated++;
            }
        }
        eventLogger.successAfterCommit("PIPE_IMPORT", Map.of("total", rows.size(), "created", created, "updated", updated, "unchanged", unchanged));
        return new PipeImportResult(rows.size(), created, updated, unchanged);
    }

    private Resolved resolve(PipeCsvCodec.ImportRow row, Map<String, Alert> alerts, Map<String, Procedure> procedures, Pipe existing) {
        List<PipeStepRequest> steps = new ArrayList<>();
        for (PipeCsvCodec.ImportStep step : row.steps()) {
            long resourceId = switch (step.type()) {
                case ALERT -> resource(row, step, alerts, "Alert").getId();
                case PROCEDURE -> resource(row, step, procedures, "Procedure").getId();
            };
            Set<PipeOutcome> continueOn = new LinkedHashSet<>();
            for (String value : step.continueOn()) {
                try { continueOn.add(PipeOutcome.valueOf(value)); }
                catch (IllegalArgumentException exception) { throw error(row, "step '" + step.key() + "' has invalid continueOn outcome '" + value + "'"); }
            }
            List<PipeBindingRequest> bindings = step.bindings().stream().map(value -> new PipeBindingRequest(
                    value.targetParameterKey(), value.sourceStepKey(), value.sourceOutputKey())).toList();
            steps.add(new PipeStepRequest(step.key(), step.type(), resourceId, Duration.ofMillis(step.timeoutMillis()), continueOn, bindings));
        }
        Pipe detailed = existing == null ? null : pipeRepository.findDetailedById(existing.getId()).orElseThrow();
        return new Resolved(row, detailed, List.copyOf(steps));
    }

    private static <T> T resource(PipeCsvCodec.ImportRow row, PipeCsvCodec.ImportStep step, Map<String, T> resources, String type) {
        T value = resources.get(key(step.resource()));
        if (value == null)
            throw error(row, type + " '" + step.resource() + "' referenced by step '" + step.key() + "' was not found");

        return value;
    }

    private static boolean unchanged(Pipe pipe, PipeCsvCodec.ImportRow row, List<PipeStepRequest> requested) {
        if (!pipe.getName().equals(row.name()) || !Objects.equals(pipe.getDescription(), row.description())
                || pipe.isEnabled() != row.enabled() || pipe.isConcurrentExecutionAllowed() != row.allowConcurrentExecutions()
                || pipe.getSteps().size() != requested.size())
            return false;
        for (int index = 0; index < requested.size(); index++) {
            PipeStep current = pipe.getSteps().get(index);
            PipeStepRequest value = requested.get(index);
            long resourceId = current.getStepType() == PipeStepType.ALERT ? current.getAlert().getId() : current.getProcedure().getId();
            if (!current.getStepKey().equals(value.key()) || current.getStepType() != value.type()
                    || resourceId != value.resourceId() || current.getTimeoutMillis() != value.timeout().toMillis()
                    || !new LinkedHashSet<>(current.getContinueOn()).equals(value.continueOn()))
                return false;
            List<String> currentBindings = current.getBindings().stream().map(binding -> binding.getTargetParameter().getParameterKey()
                    + "\u0000" + binding.getSourceStep().getStepKey() + "\u0000" + binding.getSourceOutput().getOutputKey()).toList();
            List<String> requestedBindings = value.bindings().stream().map(binding -> binding.targetParameterKey()
                    + "\u0000" + binding.sourceStepKey() + "\u0000" + binding.sourceOutputKey()).toList();
            if (!currentBindings.equals(requestedBindings))
                return false;
        }
        return true;
    }

    private static <T> Map<String, T> byName(List<T> values, Function<T, String> name) {
        return values.stream().collect(Collectors.toMap(value -> key(name.apply(value)), Function.identity(), (left, _) -> left, LinkedHashMap::new));
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }
    private static InvalidPipeImportException error(PipeCsvCodec.ImportRow row, String message) { return new InvalidPipeImportException("CSV row " + row.rowNumber() + ": " + message); }
    private record Resolved(PipeCsvCodec.ImportRow row, Pipe existing, List<PipeStepRequest> steps) { }
}
