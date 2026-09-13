package app.alertify.procedures.execution;

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.template.ParameterValueTypeCompatibility;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.ProcedureDisabledException;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.services.secret.SecretAccessService;
import app.alertify.binary.BinaryBindingService;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;

/**
 * Builds the immutable snapshot a procedure execution needs before any worker
 * is contacted: template metadata, the template source with its checksum, and
 * every parameter resolved to the value that will be sent.
 *
 * <p>Configuration and secret bindings are resolved here, while a procedure
 * binding is deliberately left unresolved: the referenced procedure only runs
 * if the template asks for it, so the orchestrator sends an invocation token
 * instead of a value. The template source must stay inside the configured
 * source root.
 */
@Service
public class ProcedureExecutionPreparationService {
    private final ProcedureRepository procedureRepository;
    private final ProcedureTemplateParameterDefinitionRepository definitionRepository;
    private final ProcedureParameterValueRepository parameterValueRepository;
    private final ConfigurationExpressionService configurationExpressionService;
    private final SecretAccessService secretAccessService;
    private final WorkerGrpcProperties properties;
    private final BinaryBindingService binaryBindingService;

    public ProcedureExecutionPreparationService(ProcedureRepository procedureRepository,
            ProcedureTemplateParameterDefinitionRepository definitionRepository,
            ProcedureParameterValueRepository parameterValueRepository,
            ConfigurationExpressionService configurationExpressionService,
            SecretAccessService secretAccessService, WorkerGrpcProperties properties, BinaryBindingService binaryBindingService) {
        this.procedureRepository = procedureRepository;
        this.definitionRepository = definitionRepository;
        this.parameterValueRepository = parameterValueRepository;
        this.configurationExpressionService = configurationExpressionService;
        this.secretAccessService = secretAccessService;
        this.properties = properties;
        this.binaryBindingService = binaryBindingService;
    }

    @Transactional(readOnly = true)
    public PreparedProcedureExecution prepare(long procedureId, boolean includeDisabled) {
        Procedure procedure = procedureRepository.findById(procedureId)
                .orElseThrow(() -> new IllegalStateException("Procedure " + procedureId + " was not found"));
        if (!procedure.isEnabled() && !includeDisabled)
            throw new ProcedureDisabledException("Procedure '" + procedure.getName() + "' is disabled");

        ProcedureTemplateDefinition template = procedure.getTemplate();
        Source source = source(template.getSourcePath());
        Map<Long, ProcedureParameterValue> configured = new HashMap<>();
        for (ProcedureParameterValue value : parameterValueRepository.findAllByOwnerIdOrdered(procedureId))
            configured.put(value.getTemplateParameter().getId(), value);

        List<ResolvedProcedureParameter> parameters = definitionRepository
                .findAllByTemplate_IdOrderByParameterOrderAscIdAsc(template.getId()).stream()
                .map(definition -> {
                    ProcedureParameterValue value = configured.get(definition.getId());
                    if (value == null) {
                        String defaultValue = definition.getDefaultValue();
                        return new ResolvedProcedureParameter(definition.getParameterKey(), definition.getJavaType(),
                                defaultValue, null, defaultValue == null, AlertParameterSource.TEXT,
                                null, null, null, false);
                    }
                    boolean binary = value.getSource() == AlertParameterSource.CONFIGURATION && value.getConfiguration().getValueType() == ConfigurationValueType.BINARY
                            || value.getSource() == AlertParameterSource.SECRET && value.getSecret().getValueType() == SecretValueType.BINARY;
                    String resolved = binary ? null : switch (value.getSource()) {
                        case TEXT -> value.getTextValue();
                        case CONFIGURATION -> configurationExpressionService
                                .getResolvedValueByName(value.getConfiguration().getName());
                        case SECRET -> secretAccessService.getValueByName(value.getSecret().getName());
                        case PROCEDURE -> null;
                    };
                    byte[] binaryZip = binary ? switch (value.getSource()) {
                        case CONFIGURATION -> binaryBindingService.configurationZip(value.getConfiguration().getId());
                        case SECRET -> binaryBindingService.secretZip(value.getSecret().getId());
                        default -> null;
                    } : null;
                    validateResolvedBinding(definition, value);
                    return new ResolvedProcedureParameter(definition.getParameterKey(), definition.getJavaType(),
                            resolved, binaryZip, resolved == null && binaryZip == null && value.getSource() != AlertParameterSource.PROCEDURE,
                            value.getSource(),
                            value.getConfiguration() == null ? null : value.getConfiguration().getId(),
                            value.getSecret() == null ? null : value.getSecret().getId(),
                            value.getReferencedProcedure() == null ? null : value.getReferencedProcedure().getId(),
                            switch (value.getSource()) {
                                case CONFIGURATION -> value.getConfiguration().isWritable();
                                case SECRET -> value.getSecret().isWritable();
                                case TEXT, PROCEDURE -> false;
                            });
                }).toList();
        return new PreparedProcedureExecution(procedure.getId(), procedure.getVersion(), procedure.getName(),
                procedure.isConcurrentExecutionAllowed(),
                template.getTemplateKey(), template.getRequiredCapability(), template.isSensitiveResult(),
                source.checksum(), source.content(), parameters);
    }

    private static void validateResolvedBinding(ProcedureTemplateParameterDefinition definition, ProcedureParameterValue value) {
        boolean compatible = switch (value.getSource()) {
            case CONFIGURATION -> ParameterValueTypeCompatibility.isConfigurationValueTypeCompatible(
                    definition.getJavaType(), value.getConfiguration().getValueType());
            case SECRET -> ParameterValueTypeCompatibility.isSecretValueTypeCompatible(
                    definition.getJavaType(), value.getSecret().getValueType());
            case TEXT, PROCEDURE -> true;
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
            throw new IllegalStateException("Procedure template source path escapes the configured root");

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return new Source(content, sha256(content));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read procedure template source " + path, exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Source(String content, String checksum) { }
}
