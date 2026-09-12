package app.alertify.procedures.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.InvalidProcedureImportException;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.api.ProcedureImportResult;
import app.alertify.procedures.api.ProcedureParameterValueRequest;
import app.alertify.procedures.api.ProcedureUpdateRequest;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;

/** Atomic CSV import/export. A shell pass permits mutually recursive procedure references. */
@Service
public class ProcedureCsvService {
    private static final long MAX_IMPORT_FILE_SIZE = 10L * 1024 * 1024;

    private final ProcedureRepository procedureRepository;
    private final ProcedureParameterValueRepository parameterRepository;
    private final ProcedureTemplateDefinitionRepository templateRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final TagRepository tagRepository;
    private final ProcedureManagementService managementService;
    private final ProcedureCsvCodec codec;
    private final ApplicationEventLogger eventLogger;

    public ProcedureCsvService(ProcedureRepository procedureRepository,
            ProcedureParameterValueRepository parameterRepository,
            ProcedureTemplateDefinitionRepository templateRepository,
            ApplicationConfigurationRepository configurationRepository,
            ApplicationSecretRepository secretRepository, TagRepository tagRepository,
            ProcedureManagementService managementService, ProcedureCsvCodec codec,
            ApplicationEventLogger eventLogger) {
        this.procedureRepository = procedureRepository;
        this.parameterRepository = parameterRepository;
        this.templateRepository = templateRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.managementService = managementService;
        this.codec = codec;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        List<Procedure> procedures = procedureRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
        Map<Long, List<ProcedureParameterValue>> values = new LinkedHashMap<>();
        for (Procedure procedure : procedures)
            values.put(procedure.getId(), parameterRepository.findAllByOwnerIdOrdered(procedure.getId()));

        eventLogger.success("PROCEDURE_EXPORT", Map.of("count", procedures.size()));
        return codec.write(procedures, values);
    }

    @Transactional
    public ProcedureImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new InvalidProcedureImportException("A non-empty CSV file is required");

        if (file.getSize() > MAX_IMPORT_FILE_SIZE) throw new InvalidProcedureImportException("CSV file exceeds the 10 MB limit");

        List<ProcedureCsvCodec.ImportRow> rows;

        try { rows = codec.read(file.getBytes()); }
        catch (IOException exception) { throw new InvalidProcedureImportException("Unable to read the CSV file", exception); }

        Map<String, ProcedureTemplateDefinition> templates = byName(templateRepository.findAll(),
                ProcedureTemplateDefinition::getTemplateKey);
        Map<String, Tag> tags = byName(tagRepository.findAllByScope(TagScope.PROCEDURE), Tag::getName);
        Map<String, Procedure> procedures = byName(procedureRepository.findAll(), Procedure::getName);
        Map<String, ApplicationConfiguration> configurations = byName(configurationRepository.findAll(),
                ApplicationConfiguration::getName);
        Map<String, ApplicationSecret> secrets = byName(secretRepository.findAll(), ApplicationSecret::getName);
        Set<Long> createdIds = new LinkedHashSet<>();
        int tagsCreated = 0;

        // Create all identities first, so A -> B -> A can be restored from one export.
        for (ProcedureCsvCodec.ImportRow row : rows) {
            ProcedureTemplateDefinition template = templates.get(key(row.templateKey()));
            if (template == null) throw error(row, "template '" + row.templateKey() + "' was not found");

            Set<Tag> resolvedTags = new LinkedHashSet<>();

            for (ProcedureCsvCodec.ImportTag imported : row.tags()) {
                Tag tag = tags.get(key(imported.name()));
                if (tag == null) {
                    tag = tagRepository.save(new Tag(TagScope.PROCEDURE, imported.name(), imported.color()));
                    tags.put(key(imported.name()), tag);
                    tagsCreated++;
                }
                resolvedTags.add(tag);
            }
            Procedure procedure = procedures.get(key(row.name()));
            if (procedure != null) {
                if (!procedure.getTemplate().getTemplateKey().equals(template.getTemplateKey()))
                    throw error(row, "procedure '" + procedure.getName() + "' already uses template '"
                            + procedure.getTemplate().getTemplateKey() + "' and its template cannot be changed");

                continue;
            }
            procedure = procedureRepository.saveAndFlush(new Procedure(template, row.name(), row.description(), ProcedureManagementService.validateCron(row.cronExpression()), row.enabled(), row.allowConcurrentExecutions(), resolvedTags));
            procedures.put(key(row.name()), procedure);
            createdIds.add(procedure.getId());
        }

        int updated = 0;
        int unchanged = 0;
        for (ProcedureCsvCodec.ImportRow row : rows) {
            Procedure procedure = procedures.get(key(row.name()));
            Set<Long> tagIds = resolveTagIds(row, tags);
            List<ProcedureParameterValueRequest> parameters = new ArrayList<>();
            for (ProcedureCsvCodec.ImportParameter parameter : row.parameters())
                parameters.add(parameter(row, parameter, configurations, secrets, procedures));

            if (!createdIds.contains(procedure.getId()) && unchanged(procedure, row, tagIds, parameters)) {
                unchanged++;
                continue;
            }
            managementService.update(procedure.getId(), new ProcedureUpdateRequest(procedure.getVersion(),
                    row.name(), row.description(), row.cronExpression(), row.enabled(), row.allowConcurrentExecutions(), parameters, tagIds));
            if (!createdIds.contains(procedure.getId())) updated++;
        }
        eventLogger.successAfterCommit("PROCEDURE_IMPORT", Map.of("total", rows.size(), "created", createdIds.size(), "updated", updated, "unchanged", unchanged, "tagsCreated", tagsCreated));

        return new ProcedureImportResult(rows.size(), createdIds.size(), updated, unchanged, tagsCreated);
    }

    private ProcedureParameterValueRequest parameter(ProcedureCsvCodec.ImportRow row, ProcedureCsvCodec.ImportParameter imported, Map<String, ApplicationConfiguration> configurations, Map<String, ApplicationSecret> secrets, Map<String, Procedure> procedures) {
        String reference = key(imported.value());
        return switch (imported.source()) {
            case TEXT -> new ProcedureParameterValueRequest(imported.key(), AlertParameterSource.TEXT,
                    imported.value(), null, null, null);
            case CONFIGURATION -> {
                ApplicationConfiguration value = configurations.get(reference);
                if (value == null) throw error(row, "configuration '" + imported.value() + "' was not found");

                yield new ProcedureParameterValueRequest(imported.key(), imported.source(), null, value.getId(), null, null);
            }
            case SECRET -> {
                ApplicationSecret value = secrets.get(reference);
                if (value == null) throw error(row, "secret '" + imported.value() + "' was not found");

                yield new ProcedureParameterValueRequest(imported.key(), imported.source(), null, null, value.getId(), null);
            }
            case PROCEDURE -> {
                Procedure value = procedures.get(reference);
                if (value == null) throw error(row, "procedure '" + imported.value() + "' was not found");

                yield new ProcedureParameterValueRequest(imported.key(), imported.source(), null, null, null, value.getId());
            }
        };
    }

    private Set<Long> resolveTagIds(ProcedureCsvCodec.ImportRow row, Map<String, Tag> tags) {
        return row.tags().stream().map(tag -> tags.get(key(tag.name())).getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean unchanged(Procedure procedure, ProcedureCsvCodec.ImportRow row, Set<Long> tagIds, List<ProcedureParameterValueRequest> parameters) {
        if (!procedure.getName().equals(row.name()) || !Objects.equals(procedure.getDescription(), row.description())
                || !procedure.getCronExpression().equals(row.cronExpression())
                || procedure.isEnabled() != row.enabled()
                || procedure.isConcurrentExecutionAllowed() != row.allowConcurrentExecutions()) return false;

        Set<Long> currentTags = procedure.getTags().stream().map(Tag::getId)
                .collect(Collectors.toCollection(TreeSet::new));

        if (!currentTags.equals(new TreeSet<>(tagIds))) return false;

        Map<String, String> current = parameterRepository.findAllByOwnerIdOrdered(procedure.getId()).stream()
                .collect(Collectors.toMap(value -> value.getTemplateParameter().getParameterKey(),
                        ProcedureCsvService::fingerprint, (left, _) -> left, LinkedHashMap::new));

        Map<String, String> requested = parameters.stream().collect(Collectors.toMap(
                ProcedureParameterValueRequest::parameterKey, ProcedureCsvService::fingerprint,
                (left, _) -> left, LinkedHashMap::new));
        return current.equals(requested);
    }

    private static String fingerprint(ProcedureParameterValue value) {
        Object reference = switch (value.getSource()) {
            case TEXT -> value.getTextValue();
            case CONFIGURATION -> value.getConfiguration().getId();
            case SECRET -> value.getSecret().getId();
            case PROCEDURE -> value.getReferencedProcedure().getId();
        };
        return value.getSource() + " " + reference;
    }

    private static String fingerprint(ProcedureParameterValueRequest value) {
        Object reference = switch (value.source()) {
            case TEXT -> value.textValue();
            case CONFIGURATION -> value.configurationId();
            case SECRET -> value.secretId();
            case PROCEDURE -> value.procedureId();
        };
        return value.source() + " " + reference;
    }

    private static <T> Map<String, T> byName(List<T> values, Function<T, String> name) {
        return values.stream().collect(Collectors.toMap(value -> key(name.apply(value)), Function.identity(),
                (left, _) -> left, LinkedHashMap::new));
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }
    private static InvalidProcedureImportException error(ProcedureCsvCodec.ImportRow row, String message) {
        return new InvalidProcedureImportException("CSV row " + row.rowNumber() + ": " + message);
    }
}
