package app.alertify.ai.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.ai.api.AiProcedureParameterSource;
import app.alertify.ai.api.AiProcedureParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.ParameterBindingMetadata;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.worker.contract.KubeconfigCredentials;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;

class AiTemplateParametersServiceTest {
    private static final String KEY = "test.Template";
    private final AlertTemplateDefinitionRepository alerts = mock(AlertTemplateDefinitionRepository.class);
    private final ProcedureTemplateDefinitionRepository procedures = mock(ProcedureTemplateDefinitionRepository.class);
    private final AlertTemplateParameterDefinitionRepository alertParameters = mock(AlertTemplateParameterDefinitionRepository.class);
    private final ProcedureTemplateParameterDefinitionRepository procedureParameters = mock(ProcedureTemplateParameterDefinitionRepository.class);
    private final ApplicationConfigurationRepository configurations = mock(ApplicationConfigurationRepository.class);
    private final ApplicationSecretRepository secrets = mock(ApplicationSecretRepository.class);
    private final ProcedureRepository procedureResources = mock(ProcedureRepository.class);
    private final PipeRepository pipes = mock(PipeRepository.class);
    private final AiTemplateParametersService service = new AiTemplateParametersService(alerts, procedures, alertParameters, procedureParameters,
            configurations, secrets, procedureResources, pipes);

    @Test
    void reportsDefaultsAndOptionalOmissionsWithoutValuesOrWrites() {
        select(List.of(parameter("required", "java.lang.String", true, null, true, false, List.of(), List.of(AlertParameterSource.TEXT)),
                parameter("timeout", "int", true, "10", true, false, List.of(), List.of(AlertParameterSource.TEXT)),
                parameter("optional", "java.lang.String", false, null, true, false, List.of(), List.of(AlertParameterSource.TEXT))));
        var result = service.validate(KEY, List.of(text("required", "")));

        assertThat(result.valid()).isTrue();
        assertThat(result.defaultsApplied()).containsExactly("timeout");
        assertThat(result.omittedOptionalParameters()).containsExactly("optional");
        assertThat(result.scope()).contains("runtime execution are not verified");
        verifyNoInteractions(configurations, secrets, procedureResources, pipes);
        verify(alertParameters, never()).save(any());
    }

    @Test
    void collectsUnknownDuplicateMissingAndInvalidLiteralErrorsWithoutEchoingValues() {
        select(List.of(parameter("count", "int", true, null, true, false, List.of(), List.of(AlertParameterSource.TEXT)),
                parameter("missing", "java.lang.String", true, null, true, false, List.of(), List.of(AlertParameterSource.TEXT))));
        var result = service.validate(KEY, List.of(text("count", "DO_NOT_ECHO_THIS"), text("count", "2"), text("unknown", "other")));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(AiTemplateParameterValidation.Issue::code)
                .contains("UNKNOWN_PARAMETER", "DUPLICATE_PARAMETER", "REQUIRED_PARAMETER", "INVALID_LITERAL");
        assertThat(JsonMapper.builder().build().writeValueAsString(result)).doesNotContain("DO_NOT_ECHO_THIS");
    }

    @Test
    void disabledBindingsStillPermitDeclaredLiteralOptions() {
        select(List.of(parameter("enabled", "boolean", true, null, false, false, List.of("false", "true"),
                List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION))));
        assertThat(service.validate(KEY, List.of(text("enabled", "false"))).valid()).isTrue();
        assertThat(service.validate(KEY, List.of(text("enabled", "FALSE"))).issues()).extracting(AiTemplateParameterValidation.Issue::code).contains("OPTION_NOT_ALLOWED");
        var bound = new AiProcedureParameterValue("enabled", AiProcedureParameterSource.CONFIGURATION, null, 3L, null, null, null);
        assertThat(service.validate(KEY, List.of(bound)).issues()).extracting(AiTemplateParameterValidation.Issue::code).contains("BINDING_NOT_ALLOWED");
        assertThat(service.candidates(KEY, "enabled", null, null).candidates().totalElements()).isZero();
        verifyNoInteractions(configurations, secrets);
    }

    @Test
    void structuredSecretCandidatesAndValidationUseTheSameTypeChecks() {
        select(List.of(parameter("credentials", KubeconfigCredentials.class.getName(), true, null, true, false, List.of(), List.of(AlertParameterSource.SECRET))));
        var wrong = secret(1L, SecretValueType.STRING, true);
        var right = secret(2L, SecretValueType.KUBECONFIG, false);
        when(secrets.findAllProjectedBy()).thenReturn(List.of(wrong, right));
        when(secrets.findProjectedById(1L)).thenReturn(Optional.of(wrong));
        when(secrets.findProjectedById(2L)).thenReturn(Optional.of(right));

        assertThat(service.candidates(KEY, "credentials", null, null).candidates().content()).extracting(AiTemplateBindingCandidates.Candidate::id).containsExactly(2L);
        assertThat(service.validate(KEY, List.of(secretValue("credentials", 1L))).valid()).isFalse();
        assertThat(service.validate(KEY, List.of(secretValue("credentials", 2L))).valid()).isTrue();
        verifyNoInteractions(configurations);
        verify(secrets, never()).findById(any());
    }

    @Test
    void binaryAndWritableRestrictionsAreAppliedBeforePagination() {
        select(List.of(parameter("snapshot", "[B", true, null, true, true, List.of(), List.of(AlertParameterSource.CONFIGURATION))));
        List<ParameterBindingMetadata.Configuration> entries = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            var entry = configuration(id, id == 1 ? ConfigurationValueType.STRING : ConfigurationValueType.BINARY, id != 2);
            entries.add(entry);
            when(configurations.findProjectedById(id)).thenReturn(Optional.of(entry));
        }
        when(configurations.findAllProjectedBy()).thenReturn(entries);
        var page = service.candidates(KEY, "snapshot", 1, 2).candidates();
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting(AiTemplateBindingCandidates.Candidate::id).containsExactly(5L, 6L);
        for (long id = 1; id <= 6; id++) {
            var value = new AiProcedureParameterValue("snapshot", AiProcedureParameterSource.CONFIGURATION, null, id, null, null, null);
            assertThat(service.validate(KEY, List.of(value)).valid()).isEqualTo(id > 2);
        }
        assertThat(service.candidates(KEY, "snapshot", Integer.MAX_VALUE, 100).candidates().content()).isEmpty();
    }

    @Test
    void emptyDeclaredTypeListsAllowCompatibleScalarSecretTypes() {
        select(List.of(parameter("label", "java.lang.String", true, null, true, false, List.of(), List.of(AlertParameterSource.SECRET))));
        var entries = List.of(secret(1L, SecretValueType.STRING, false), secret(2L, SecretValueType.EXPRESSION, false), secret(3L, SecretValueType.DB_SECRET, false));
        when(secrets.findAllProjectedBy()).thenReturn(entries);
        assertThat(service.candidates(KEY, "label", null, null).candidates().content()).extracting(AiTemplateBindingCandidates.Candidate::id).containsExactly(1L, 2L);
    }

    @Test
    void procedurePipeBindingsExposeEnabledMetadataWithoutExecutingAnything() {
        var template = new ProcedureTemplateDefinition(KEY, "n", "d", "test.java", WorkerCapability.STANDARD, false, List.of());
        ReflectionTestUtils.setField(template, "id", 7L);
        when(procedures.findByTemplateKey(KEY)).thenReturn(Optional.of(template));
        var parameter = new ProcedureTemplateParameterDefinition(template, "pipe", "n", "d", "app.alertify.pipes.Pipe", List.of(), true, null, false,
                1, true, List.of(AlertParameterSource.PIPE), List.of(), List.of());
        when(procedureParameters.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L)).thenReturn(List.of(parameter));
        var pipe = mock(ParameterBindingMetadata.Executable.class);
        when(pipe.getId()).thenReturn(4L);
        when(pipe.getName()).thenReturn("Backup pipe");
        when(pipe.isEnabled()).thenReturn(false);
        when(pipes.findAllProjectedBy()).thenReturn(List.of(pipe));
        when(pipes.findProjectedById(4L)).thenReturn(Optional.of(pipe));

        assertThat(service.candidates(KEY, "pipe", null, null).candidates().content().getFirst().enabled()).isFalse();
        var value = new AiProcedureParameterValue("pipe", AiProcedureParameterSource.PIPE, null, null, null, null, 4L);
        assertThat(service.validate(KEY, List.of(value)).valid()).isTrue();
        verify(pipes, never()).findDetailedById(any());
    }

    @Test
    void rejectsMissingReferencesInvalidShapesAndWrongSelectedKeys() {
        select(List.of(parameter("credentials", "java.lang.String", true, null, true, false, List.of(), List.of(AlertParameterSource.SECRET))));
        assertThat(service.validate(KEY, List.of(secretValue("credentials", 99L))).issues()).extracting(AiTemplateParameterValidation.Issue::code).contains("REFERENCE_NOT_FOUND");
        assertThat(service.validate(KEY, List.of(secretValue("credentials", 0L))).issues()).extracting(AiTemplateParameterValidation.Issue::code).contains("INVALID_ASSIGNMENT");
        assertThatThrownBy(() -> service.candidates(KEY, "notAParameter", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.candidates(KEY, "credentials", -1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.candidates(KEY, "credentials", 0, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operationalReadFailuresAreNotReportedAsEmptyCandidates() {
        select(List.of(parameter("label", "java.lang.String", true, null, true, false, List.of(), List.of(AlertParameterSource.SECRET))));
        when(secrets.findAllProjectedBy()).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(() -> service.candidates(KEY, "label", null, null)).isInstanceOf(IllegalStateException.class);
    }

    private void select(List<AlertTemplateParameterDefinition> parameters) {
        var template = new AlertTemplateDefinition(KEY, "n", "d", "test.java", WorkerCapability.STANDARD);
        ReflectionTestUtils.setField(template, "id", 7L);
        when(alerts.findByTemplateKey(KEY)).thenReturn(Optional.of(template));
        when(alertParameters.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(7L)).thenReturn(parameters);
    }

    private static AlertTemplateParameterDefinition parameter(String key, String type, boolean required, String defaultValue, boolean binding, boolean writable, List<String> options, List<AlertParameterSource> sources) {
        var template = new AlertTemplateDefinition(KEY, "n", "d", "test.java", WorkerCapability.STANDARD);
        return new AlertTemplateParameterDefinition(template, key, "n", "d", type, options, binding, writable, defaultValue, false, 1, required, sources, List.of(), List.of());
    }

    private static AiProcedureParameterValue text(String key, String value) {
        return new AiProcedureParameterValue(key, AiProcedureParameterSource.TEXT, value, null, null, null, null);
    }

    private static AiProcedureParameterValue secretValue(String key, Long id) {
        return new AiProcedureParameterValue(key, AiProcedureParameterSource.SECRET, null, null, id, null, null);
    }

    private static ParameterBindingMetadata.Secret secret(Long id, SecretValueType type, boolean writable) {
        var value = mock(ParameterBindingMetadata.Secret.class);
        when(value.getId()).thenReturn(id);
        when(value.getName()).thenReturn("Secret " + id);
        when(value.getValueType()).thenReturn(type);
        when(value.isWritable()).thenReturn(writable);
        return value;
    }

    private static ParameterBindingMetadata.Configuration configuration(Long id, ConfigurationValueType type, boolean writable) {
        var value = mock(ParameterBindingMetadata.Configuration.class);
        when(value.getId()).thenReturn(id);
        when(value.getName()).thenReturn("Config " + id);
        when(value.getValueType()).thenReturn(type);
        when(value.isWritable()).thenReturn(writable);
        return value;
    }
}
