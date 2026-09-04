package app.alertify.alerts.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.services.secret.SecretAccessService;

@Service
public class AlertExecutionPreparationService {

    private final AlertRepository alertRepository;
    private final AlertTemplateParameterDefinitionRepository definitionRepository;
    private final AlertParameterValueRepository parameterValueRepository;
    private final AlertStateRepository stateRepository;
    private final ConfigurationExpressionService configurationExpressionService;
    private final SecretAccessService secretAccessService;
    private final WorkerGrpcProperties properties;

    public AlertExecutionPreparationService(AlertRepository alertRepository, AlertTemplateParameterDefinitionRepository definitionRepository, AlertParameterValueRepository parameterValueRepository, AlertStateRepository stateRepository, ConfigurationExpressionService configurationExpressionService, SecretAccessService secretAccessService, WorkerGrpcProperties properties) {
        this.alertRepository = alertRepository;
        this.definitionRepository = definitionRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.stateRepository = stateRepository;
        this.configurationExpressionService = configurationExpressionService;
        this.secretAccessService = secretAccessService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<PreparedAlertExecution> prepare(Long alertId) {
        return prepare(alertId, false);
    }

    /**
     * @param includeDisabled run a disabled alert anyway, as a manual run does.
     */
    @Transactional(readOnly = true)
    public Optional<PreparedAlertExecution> prepare(Long alertId, boolean includeDisabled) {
        Alert alert = alertRepository.findById(alertId).orElse(null);
        if (alert == null || (!alert.isEnabled() && !includeDisabled))
            return Optional.empty();

        AlertTemplateDefinition template = alert.getTemplate();
        Source source = source(template.getSourcePath());
        Map<Long, AlertParameterValue> configuredValues = new HashMap<>();
        for (AlertParameterValue value : parameterValueRepository.findAllByAlertIdOrdered(alertId))
            configuredValues.put(value.getTemplateParameter().getId(), value);

        List<ResolvedAlertParameter> parameters = definitionRepository
                .findAllByTemplate_IdOrderByParameterOrderAscIdAsc(template.getId())
                .stream()
                .map(definition -> resolve(definition, configuredValues.get(definition.getId())))
                .toList();
        String state = stateRepository.findById(alertId).map(value -> value.getState()).orElse("");
        return Optional.of(new PreparedAlertExecution(
                alert.getId(), alert.getName(), template.getTemplateKey(),
                template.getRequiredCapability(), source.checksum(), source.content(), state,
                parameters
        ));
    }

    private ResolvedAlertParameter resolve(AlertTemplateParameterDefinition definition, AlertParameterValue configured) {
        if (configured == null) {
            String defaultValue = definition.getDefaultValue();
            return new ResolvedAlertParameter(
                    definition.getParameterKey(), definition.getJavaType(), defaultValue,
                    defaultValue == null, null, false
            );
        }
        String value = switch (configured.getSource()) {
            case TEXT -> configured.getTextValue();
            case CONFIGURATION -> configurationExpressionService.getResolvedValueByName(configured.getConfiguration().getName());
            case SECRET -> secretAccessService.getValueByName(configured.getSecret().getName());
        };
        return new ResolvedAlertParameter(
                definition.getParameterKey(), definition.getJavaType(), value, value == null,
                configured.getSource() == AlertParameterSource.CONFIGURATION
                        ? configured.getConfiguration().getId()
                        : null,
                configured.getSource() == AlertParameterSource.CONFIGURATION
                        && configured.getConfiguration().isWritable()
        );
    }

    private Source source(String relativePath) {
        WorkerGrpcProperties.Execution execution = properties.execution();
        if (execution == null || execution.sourceRoot() == null)
            throw new IllegalStateException("worker.grpc.execution.source-root must be configured");

        Path root = execution.sourceRoot().toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root))
            throw new IllegalStateException("Alert template source path escapes the configured root");

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return new Source(content, sha256(content));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read alert template source " + path, exception);
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Source(String content, String checksum) {
    }
}
