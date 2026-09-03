package app.alertify.alerts.service;

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
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertImportResult;
import app.alertify.alerts.api.AlertParameterValueRequest;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.InvalidAlertImportException;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;

/**
 * CSV export and import of alert definitions. Import upserts by name and
 * delegates to {@link AlertManagementService} so cron validation, parameter
 * defaults and rescheduling behave exactly as they do for the HTTP API.
 */
@Service
public class AlertCsvService {

    private static final long MAX_IMPORT_FILE_SIZE = 10L * 1024 * 1024;

    private final AlertRepository alertRepository;
    private final AlertParameterValueRepository parameterValueRepository;
    private final AlertTemplateDefinitionRepository templateRepository;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final TagRepository tagRepository;
    private final AlertManagementService alertManagementService;
    private final AlertCsvCodec csvCodec;

    AlertCsvService(AlertRepository alertRepository, AlertParameterValueRepository parameterValueRepository, AlertTemplateDefinitionRepository templateRepository, ApplicationConfigurationRepository configurationRepository, ApplicationSecretRepository secretRepository, TagRepository tagRepository, AlertManagementService alertManagementService, AlertCsvCodec csvCodec) {
        this.alertRepository = alertRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.templateRepository = templateRepository;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.alertManagementService = alertManagementService;
        this.csvCodec = csvCodec;
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        List<Alert> alerts = alertRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
        Map<Long, List<AlertParameterValue>> valuesByAlertId = new LinkedHashMap<>();
        for (Alert alert : alerts)
            valuesByAlertId.put(alert.getId(), parameterValueRepository.findAllByAlertIdOrdered(alert.getId()));

        return csvCodec.write(alerts, valuesByAlertId);
    }

    @Transactional
    public AlertImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidAlertImportException("A non-empty CSV file is required");
        }
        if (file.getSize() > MAX_IMPORT_FILE_SIZE) {
            throw new InvalidAlertImportException("CSV file exceeds the 10 MB limit");
        }

        List<AlertCsvCodec.ImportRow> rows;
        try {
            rows = csvCodec.read(file.getBytes());
        } catch (IOException exception) {
            throw new InvalidAlertImportException("Unable to read the CSV file", exception);
        }

        Map<String, Alert> alertsByName = byLowercaseName(alertRepository.findAll(), Alert::getName);
        Map<String, AlertTemplateDefinition> templatesByKey = byLowercaseName(
                templateRepository.findAll(), AlertTemplateDefinition::getTemplateKey
        );
        Map<String, Tag> tagsByName = byLowercaseName(tagRepository.findAllByScope(TagScope.ALERT), Tag::getName);
        Map<String, ApplicationConfiguration> configurationsByName = byLowercaseName(
                configurationRepository.findAll(), ApplicationConfiguration::getName
        );
        Map<String, ApplicationSecret> secretsByName = byLowercaseName(
                secretRepository.findAll(), ApplicationSecret::getName
        );

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int tagsCreated = 0;

        for (AlertCsvCodec.ImportRow row : rows) {
            AlertTemplateDefinition template = templatesByKey.get(row.templateKey().toLowerCase(Locale.ROOT));
            if (template == null) {
                throw rowError(row, "template '" + row.templateKey() + "' was not found");
            }

            Set<Long> tagIds = new LinkedHashSet<>();
            for (AlertCsvCodec.ImportTag importedTag : row.tags()) {
                String tagKey = importedTag.name().toLowerCase(Locale.ROOT);
                Tag tag = tagsByName.get(tagKey);
                if (tag == null) {
                    tag = tagRepository.save(new Tag(TagScope.ALERT, importedTag.name(), importedTag.color()));
                    tagsByName.put(tagKey, tag);
                    tagsCreated++;
                }
                tagIds.add(tag.getId());
            }

            List<AlertParameterValueRequest> parameters = new ArrayList<>();
            for (AlertCsvCodec.ImportParameter parameter : row.parameters())
                parameters.add(toParameterRequest(row, parameter, configurationsByName, secretsByName));

            String alertKey = row.name().toLowerCase(Locale.ROOT);
            Alert alert = alertsByName.get(alertKey);
            if (alert == null) {
                alertManagementService.create(
                        new AlertCreateRequest(
                                template.getId(), row.name(), row.description(), row.cronExpression(),
                                row.enabled(), row.allowConcurrentExecutions(), parameters, tagIds
                        )
                );
                created++;
                continue;
            }

            if (!alert.getTemplate().getTemplateKey().equals(template.getTemplateKey())) {
                throw rowError(
                        row,
                        "alert '" + alert.getName() + "' already uses template '"
                                + alert.getTemplate().getTemplateKey() + "' and its template cannot be changed"
                );
            }

            if (isUnchanged(alert, row, tagIds, parameters)) {
                unchanged++;
                continue;
            }

            alertManagementService.update(
                    alert.getId(),
                    new AlertUpdateRequest(
                            alert.getVersion(), row.name(), row.description(), row.cronExpression(),
                            row.enabled(), row.allowConcurrentExecutions(), parameters, tagIds
                    )
            );
            updated++;
        }

        return new AlertImportResult(rows.size(), created, updated, unchanged, tagsCreated);
    }

    private AlertParameterValueRequest toParameterRequest(AlertCsvCodec.ImportRow row, AlertCsvCodec.ImportParameter parameter, Map<String, ApplicationConfiguration> configurationsByName, Map<String, ApplicationSecret> secretsByName) {
        String reference = parameter.value().toLowerCase(Locale.ROOT);
        return switch (parameter.source()) {
            case TEXT -> new AlertParameterValueRequest(
                    parameter.key(), AlertParameterSource.TEXT, parameter.value(), null, null
            );
            case CONFIGURATION -> {
                ApplicationConfiguration configuration = configurationsByName.get(reference);
                if (configuration == null)
                    throw rowError(row, "configuration '" + parameter.value() + "' was not found");

                yield new AlertParameterValueRequest(
                        parameter.key(), AlertParameterSource.CONFIGURATION, null, configuration.getId(), null
                );
            }
            case SECRET -> {
                ApplicationSecret secret = secretsByName.get(reference);
                if (secret == null)
                    throw rowError(row, "secret '" + parameter.value() + "' was not found");

                yield new AlertParameterValueRequest(
                        parameter.key(), AlertParameterSource.SECRET, null, null, secret.getId()
                );
            }
        };
    }

    /**
     * Skips alerts whose CSV row matches the stored definition so the import
     * neither bumps their version nor reschedules them.
     */
    private boolean isUnchanged(Alert alert, AlertCsvCodec.ImportRow row, Set<Long> tagIds, List<AlertParameterValueRequest> parameters) {
        if (!alert.getName().equals(row.name())
                || !Objects.equals(alert.getDescription(), row.description())
                || !alert.getCronExpression().equals(row.cronExpression())
                || alert.isEnabled() != row.enabled()
                || alert.isConcurrentExecutionAllowed() != row.allowConcurrentExecutions()) {
            return false;
        }

        Set<Long> currentTagIds = alert.getTags().stream().map(Tag::getId).collect(Collectors.toCollection(TreeSet::new));
        if (!currentTagIds.equals(new TreeSet<>(tagIds)))
            return false;

        Map<String, String> current = parameterValueRepository.findAllByAlertIdOrdered(alert.getId()).stream()
                .collect(
                        Collectors.toMap(
                                value -> value.getTemplateParameter().getParameterKey(),
                                AlertCsvService::fingerprint,
                                (left, _) -> left,
                                LinkedHashMap::new
                        )
                );
        Map<String, String> requested = parameters.stream()
                .collect(
                        Collectors.toMap(
                                AlertParameterValueRequest::parameterKey,
                                AlertCsvService::fingerprint,
                                (left, _) -> left,
                                LinkedHashMap::new
                        )
                );
        return current.equals(requested);
    }

    private static String fingerprint(AlertParameterValue value) {
        String reference = switch (value.getSource()) {
            case TEXT -> value.getTextValue();
            case CONFIGURATION -> String.valueOf(value.getConfiguration().getId());
            case SECRET -> String.valueOf(value.getSecret().getId());
        };
        return value.getSource() + " " + reference;
    }

    private static String fingerprint(AlertParameterValueRequest request) {
        String reference = switch (request.source()) {
            case TEXT -> request.textValue();
            case CONFIGURATION -> String.valueOf(request.configurationId());
            case SECRET -> String.valueOf(request.secretId());
        };
        return request.source() + " " + reference;
    }

    private static <T> Map<String, T> byLowercaseName(List<T> values, java.util.function.Function<T, String> name) {
        return values.stream().collect(
                Collectors.toMap(
                        value -> name.apply(value).toLowerCase(Locale.ROOT),
                        value -> value,
                        (left, _) -> left,
                        LinkedHashMap::new
                )
        );
    }

    private static InvalidAlertImportException rowError(AlertCsvCodec.ImportRow row, String message) {
        return new InvalidAlertImportException("CSV row " + row.rowNumber() + ": " + message);
    }
}
