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
import app.alertify.alerts.model.AlertState;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.AlertExecutionValue;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.alerts.template.ParameterValueTypeCompatibility;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.templates.PlaywrightPageAlertTemplate;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.services.secret.SecretAccessContext;
import app.alertify.services.secret.SecretAccessService;
import app.alertify.binary.BinaryBindingService;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;

/**
 * Builds the immutable snapshot an alert execution needs before any worker is
 * contacted: template metadata, the template source with its checksum, the
 * persisted alert state, and every parameter resolved to the value that will be
 * sent. Configuration and secret bindings are resolved here, while a procedure
 * binding is left unresolved so it is only invoked if the template asks for it.
 */
@Service
public class AlertExecutionPreparationService {

    private final AlertRepository alertRepository;
    private final AlertTemplateParameterDefinitionRepository definitionRepository;
    private final AlertParameterValueRepository parameterValueRepository;
    private final AlertStateRepository stateRepository;
    private final ConfigurationExpressionService configurationExpressionService;
    private final SecretAccessService secretAccessService;
    private final ApplicationConfigurationRepository configurationRepository;
    private final ApplicationSecretRepository secretRepository;
    private final BinaryBindingService binaryBindingService;
    private final WorkerGrpcProperties properties;

    public AlertExecutionPreparationService(AlertRepository alertRepository, AlertTemplateParameterDefinitionRepository definitionRepository, AlertParameterValueRepository parameterValueRepository, AlertStateRepository stateRepository, ConfigurationExpressionService configurationExpressionService, SecretAccessService secretAccessService, ApplicationConfigurationRepository configurationRepository, ApplicationSecretRepository secretRepository, WorkerGrpcProperties properties, BinaryBindingService binaryBindingService) {
        this.alertRepository = alertRepository;
        this.definitionRepository = definitionRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.stateRepository = stateRepository;
        this.configurationExpressionService = configurationExpressionService;
        this.secretAccessService = secretAccessService;
        this.configurationRepository = configurationRepository;
        this.secretRepository = secretRepository;
        this.binaryBindingService = binaryBindingService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<PreparedAlertExecution> prepare(Long alertId) {
        return prepareInternal(alertId, false);
    }

    /**
     * @param includeDisabled run a disabled alert anyway, as a manual run does.
     */
    @Transactional(readOnly = true)
    public Optional<PreparedAlertExecution> prepare(Long alertId, boolean includeDisabled) {
        return prepareInternal(alertId, includeDisabled);
    }

    /**
     * Prepares a one-off execution of {@code template} with caller-supplied parameters and no
     * persisted alert behind it, for example to probe credentials before saving a secret.
     * The execution carries alert id 0 and an empty state; nothing about it is persisted.
     */
    public PreparedAlertExecution prepareAdHoc(AlertTemplateDefinition template, String executionName, List<ResolvedAlertParameter> parameters) {
        Source source = source(template.getSourcePath());
        SecretAccessContext accessContext = SecretAccessContext.alert(0, executionName);
        return new PreparedAlertExecution(0, executionName, template.getTemplateKey(),
                template.getRequiredCapability(), source.checksum(), source.content(), "", List.copyOf(parameters),
                prepareValues(template.getTemplateKey(), parameters, accessContext));
    }

    private Optional<PreparedAlertExecution> prepareInternal(Long alertId, boolean includeDisabled) {
        Alert alert = alertRepository.findById(alertId).orElse(null);
        if (alert == null || (!alert.isEnabled() && !includeDisabled))
            return Optional.empty();

        AlertTemplateDefinition template = alert.getTemplate();
        SecretAccessContext accessContext = SecretAccessContext.alert(alert.getId(), alert.getName());
        Source source = source(template.getSourcePath());
        Map<Long, AlertParameterValue> configuredValues = new HashMap<>();
        for (AlertParameterValue value : parameterValueRepository.findAllByAlertIdOrdered(alertId))
            configuredValues.put(value.getTemplateParameter().getId(), value);

        List<ResolvedAlertParameter> parameters = definitionRepository
                .findAllByTemplate_IdOrderByParameterOrderAscIdAsc(template.getId())
                .stream()
                .map(definition -> resolve(definition, configuredValues.get(definition.getId()), accessContext))
                .toList();
        String state = stateRepository.findById(alertId).map(AlertState::getState).orElse("");
        return Optional.of(new PreparedAlertExecution(
                alert.getId(), alert.getName(), template.getTemplateKey(),
                template.getRequiredCapability(), source.checksum(), source.content(), state,
                parameters, prepareValues(template.getTemplateKey(), parameters, accessContext)
        ));
    }

    private List<AlertExecutionValue> prepareValues(String templateClassName, List<ResolvedAlertParameter> parameters, SecretAccessContext accessContext) {
        if (!PlaywrightPageAlertTemplate.class.getName().equals(templateClassName))
            return List.of();

        String steps = parameters.stream()
                .filter(parameter -> "steps".equals(parameter.name()))
                .findFirst()
                .map(ResolvedAlertParameter::value)
                .orElse(null);
        return PlaywrightPageAlertTemplate.requiredValues(steps).stream()
                .map(reference -> resolveValue(reference, accessContext))
                .toList();
    }

    private AlertExecutionValue resolveValue(PlaywrightPageAlertTemplate.ValueReference reference, SecretAccessContext accessContext) {
        return switch (reference.source()) {
            case CONFIGURATION -> {
                var configuration = configurationRepository.findByNameIgnoreCase(reference.name()).orElseThrow(
                        () -> new ResourceNotFoundException("Configuration '" + reference.name() + "' was not found")
                );
                if (configuration.getValueType() == ConfigurationValueType.BINARY)
                    throw new IllegalArgumentException("Configuration '" + configuration.getName() + "' cannot be used by FILL because it is binary");

                yield new AlertExecutionValue(AlertExecutionValueSource.CONFIGURATION, configuration.getName(), configurationExpressionService.getResolvedValueByName(configuration.getName()));
            }
            case SECRET -> {
                var secret = secretRepository.findByNameIgnoreCase(reference.name()).orElseThrow(
                        () -> new ResourceNotFoundException("Secret '" + reference.name() + "' was not found")
                );
                if (secret.getValueType() != SecretValueType.STRING && secret.getValueType() != SecretValueType.EXPRESSION)
                    throw new IllegalArgumentException("Secret '" + secret.getName() + "' cannot be used by FILL because it is not textual");

                yield new AlertExecutionValue(AlertExecutionValueSource.SECRET, secret.getName(), secretAccessService.getValueByName(secret.getName(), accessContext));
            }
        };
    }

    private ResolvedAlertParameter resolve(AlertTemplateParameterDefinition definition, AlertParameterValue configured, SecretAccessContext accessContext) {
        if (configured == null) {
            String defaultValue = definition.getDefaultValue();
            return new ResolvedAlertParameter(
                    definition.getParameterKey(), definition.getJavaType(), defaultValue, null,
                    defaultValue == null, AlertParameterSource.TEXT, null, null, null, false
            );
        }
        boolean binary = configured.getSource() == AlertParameterSource.CONFIGURATION && configured.getConfiguration().getValueType() == ConfigurationValueType.BINARY
                || configured.getSource() == AlertParameterSource.SECRET && configured.getSecret().getValueType() == SecretValueType.BINARY;
        String value = binary ? null : switch (configured.getSource()) {
            case TEXT -> configured.getTextValue();
            case CONFIGURATION -> configurationExpressionService.getResolvedValueByName(configured.getConfiguration().getName());
            case SECRET -> secretAccessService.getValueByName(configured.getSecret().getName(), accessContext);
            case PROCEDURE -> null;
            case PIPE, PIPE_OUTPUT -> throw new IllegalArgumentException("Pipe sources are not valid for Alert parameters");
        };
        byte[] binaryZip = binary ? switch (configured.getSource()) {
            case CONFIGURATION -> binaryBindingService.configurationZip(configured.getConfiguration().getId());
            case SECRET -> secretAccessService.getBinaryValue(configured.getSecret(), accessContext);
            default -> null;
        } : null;
        validateResolvedBinding(definition, configured);
        return new ResolvedAlertParameter(
                definition.getParameterKey(), definition.getJavaType(), value, binaryZip,
                value == null && binaryZip == null && configured.getSource() != AlertParameterSource.PROCEDURE,
                configured.getSource(),
                configured.getSource() == AlertParameterSource.CONFIGURATION
                        ? configured.getConfiguration().getId()
                        : null,
                configured.getSource() == AlertParameterSource.SECRET
                        ? configured.getSecret().getId()
                        : null,
                configured.getSource() == AlertParameterSource.PROCEDURE
                        ? configured.getProcedure().getId()
                        : null,
                switch (configured.getSource()) {
                    case CONFIGURATION -> configured.getConfiguration().isWritable();
                    case SECRET -> configured.getSecret().isWritable();
                    case TEXT, PROCEDURE, PIPE, PIPE_OUTPUT -> false;
                },
                switch (configured.getSource()) {
                    case CONFIGURATION -> configured.getConfiguration().getVersion();
                    case SECRET -> configured.getSecret().getVersion();
                    case TEXT, PROCEDURE, PIPE, PIPE_OUTPUT -> null;
                }
        );
    }

    private static void validateResolvedBinding(AlertTemplateParameterDefinition definition, AlertParameterValue configured) {
        boolean writable = switch (configured.getSource()) {
            case CONFIGURATION -> configured.getConfiguration().isWritable();
            case SECRET -> configured.getSecret().isWritable();
            case TEXT, PROCEDURE, PIPE, PIPE_OUTPUT -> false;
        };
        if (definition.isWritableBindingRequired() && !writable)
            throw new IllegalArgumentException("Parameter '" + definition.getParameterKey() + "' requires a writable binding");

        boolean compatible = switch (configured.getSource()) {
            case CONFIGURATION -> ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(
                    definition.getJavaType(), configured.getConfiguration().getValueType());
            case SECRET -> ParameterValueTypeCompatibility.isSecretValueTypeCompatible(
                    definition.getJavaType(), configured.getSecret().getValueType());
            case TEXT, PROCEDURE -> true;
            case PIPE, PIPE_OUTPUT -> false;
        };
        if (!compatible)
            throw new IllegalArgumentException("Parameter '" + definition.getParameterKey() + "' and its binding have incompatible value types");
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
