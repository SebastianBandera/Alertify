package app.alertify.ai.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.alerts.api.AlertTemplateParameterResponse;
import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.service.AlertCatalogService;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.procedures.api.ProcedureTemplateOutputResponse;
import app.alertify.procedures.api.ProcedureTemplateParameterResponse;
import app.alertify.procedures.api.ProcedureTemplateResponse;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;

class AiTemplateDetailsServiceTest {
    private static final String ALERT_KEY = "app.alertify.alerts.templates.SampleAlertTemplate";
    private static final String PROCEDURE_KEY = "app.alertify.procedures.templates.SampleProcedureTemplate";
    private static final String ALERT_PATH = "app/alertify/alerts/templates/SampleAlertTemplate.java";
    private static final String PROCEDURE_PATH = "app/alertify/procedures/templates/SampleProcedureTemplate.java";

    @TempDir
    Path directory;

    private final AlertTemplateDefinitionRepository alertTemplates = mock(AlertTemplateDefinitionRepository.class);
    private final ProcedureTemplateDefinitionRepository procedureTemplates = mock(ProcedureTemplateDefinitionRepository.class);
    private final AlertCatalogService alerts = mock(AlertCatalogService.class);
    private final ProcedureCatalogService procedures = mock(ProcedureCatalogService.class);

    @Test
    void combinesTheExactSelectedAlertMetadataAndSourceInOneRead() throws Exception {
        String source = "package app.alertify.alerts.templates; public class SampleAlertTemplate {}";
        write(ALERT_PATH, source);
        when(alertTemplates.findByTemplateKey(ALERT_KEY)).thenReturn(Optional.of(alertDefinition(ALERT_PATH)));
        var parameter = new AlertTemplateParameterResponse(2L, 0, "warningDays", "days.label", "days.description", "int",
                List.of("7", "30"), true, false, "30", false, 1, true, List.of(AlertParameterSource.TEXT), List.of(), List.of(), null, null);
        var definition = new AlertTemplateResponse(8L, 3L, ALERT_KEY, "alert.name", "alert.description", WorkerCapability.STANDARD,
                true, List.of(), 2, List.of(parameter), null, null);
        when(alerts.template(null, ALERT_KEY)).thenReturn(definition);

        AiTemplateDetails details = service().details(" " + ALERT_KEY + " ");

        assertThat(details.templateType()).isEqualTo("ALERT");
        assertThat(details.templateKey()).isEqualTo(ALERT_KEY);
        assertThat(details.alertTemplate()).isSameAs(definition);
        assertThat(details.procedureTemplate()).isNull();
        assertThat(details.source().templateKey()).isEqualTo(ALERT_KEY);
        assertThat(details.source().content()).isEqualTo(source);
        assertThat(details.source().path()).isEqualTo("backend/alert-templates/src/main/java/" + ALERT_PATH);
        assertThat(details.usageRules()).anyMatch(rule -> rule.contains("key to parameterKey") || rule.contains("to parameterKey"));
        assertThat(details.usageRules()).anyMatch(rule -> rule.contains("not translated labels"));
        assertThat(details.usageRules()).anyMatch(rule -> rule.contains("does not execute"));
        String json = JsonMapper.builder().build().writeValueAsString(details);
        assertThat(json).contains("warningDays", "defaultValue", "allowedSources", source);
        verifyNoInteractions(procedures);
        verify(alerts, never()).templates();
    }

    @Test
    void preservesAllProcedureOutputsSensitiveFlagsAndBindingRestrictions() throws Exception {
        String source = "package app.alertify.procedures.templates; public class SampleProcedureTemplate {}";
        write(PROCEDURE_PATH, source);
        when(procedureTemplates.findByTemplateKey(PROCEDURE_KEY)).thenReturn(Optional.of(procedureDefinition()));
        var parameter = new ProcedureTemplateParameterResponse(4L, 0, "credentials", "credentials.label", "credentials.description", "java.lang.String",
                List.of(), true, "", false, 1, true, List.of(AlertParameterSource.SECRET), List.of(), List.of("STRING"), null, null);
        var output = new ProcedureTemplateOutputResponse(5L, 0, "backup", 1, null, null);
        var definition = new ProcedureTemplateResponse(9L, 2L, PROCEDURE_KEY, "procedure.name", "procedure.description", WorkerCapability.STANDARD,
                true, List.of(), 1, List.of(parameter), List.of(output), null, null);
        when(procedures.template(null, PROCEDURE_KEY)).thenReturn(definition);

        AiTemplateDetails details = service().details(PROCEDURE_KEY);

        assertThat(details.templateType()).isEqualTo("PROCEDURE");
        assertThat(details.templateKey()).isEqualTo(PROCEDURE_KEY);
        assertThat(details.alertTemplate()).isNull();
        assertThat(details.procedureTemplate()).isSameAs(definition);
        assertThat(details.procedureTemplate().sensitiveResult()).isTrue();
        assertThat(details.procedureTemplate().outputs()).containsExactly(output);
        assertThat(details.procedureTemplate().parameters()).containsExactly(parameter);
        assertThat(details.source().templateType()).isEqualTo("PROCEDURE");
        assertThat(details.source().content()).isEqualTo(source);
        verifyNoInteractions(alerts);
        verify(procedures, never()).templates();
    }

    @Test
    void rejectsBlankSelectorsBeforeAnyRepositoryOrCatalogRead() {
        for (String key : new String[] { null, "", "   " })
            assertThatThrownBy(() -> service().details(key)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(alertTemplates, procedureTemplates, alerts, procedures);
    }

    @Test
    void neverGuessesNamesOrUsesAnUnregisteredKeyAsAPath() {
        for (String key : List.of("../../.env", "SampleAlertTemplate", "42"))
            assertThatThrownBy(() -> service().details(key)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(alerts, procedures);
    }

    @Test
    void rejectsACrossCatalogCollisionInsteadOfSelectingArbitrarily() {
        when(alertTemplates.findByTemplateKey(ALERT_KEY)).thenReturn(Optional.of(alertDefinition(ALERT_PATH)));
        when(procedureTemplates.findByTemplateKey(ALERT_KEY)).thenReturn(Optional.of(procedureDefinition()));

        assertThatThrownBy(() -> service().details(ALERT_KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both an alert and a procedure");
        verifyNoInteractions(alerts, procedures);
    }

    @Test
    void failsExplicitlyWhenSourceIsUnavailableInsteadOfReturningAnIncompleteReference() {
        when(alertTemplates.findByTemplateKey(ALERT_KEY)).thenReturn(Optional.of(alertDefinition(ALERT_PATH)));

        assertThatThrownBy(() -> service().details(ALERT_KEY)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retainsSourceSizeAndPathBoundaries() throws Exception {
        write(ALERT_PATH, "x".repeat(96 * 1024 + 1));
        when(alertTemplates.findByTemplateKey(ALERT_KEY)).thenReturn(Optional.of(alertDefinition(ALERT_PATH)));
        assertThatThrownBy(() -> service().details(ALERT_KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size limit");

        when(alertTemplates.findByTemplateKey(ALERT_KEY)).thenReturn(Optional.of(alertDefinition("../outside.java")));
        assertThatThrownBy(() -> service().details(ALERT_KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("path is invalid");
    }

    private AiTemplateDetailsService service() {
        return new AiTemplateDetailsService(alertTemplates, procedureTemplates, alerts, procedures,
                new AiTemplateSourceService(alertTemplates, procedureTemplates, directory.toString()), new AiTemplateExamplesService());
    }

    private static AlertTemplateDefinition alertDefinition(String path) {
        return new AlertTemplateDefinition(ALERT_KEY, "alert.name", "alert.description", path, WorkerCapability.STANDARD);
    }

    private static ProcedureTemplateDefinition procedureDefinition() {
        return new ProcedureTemplateDefinition(PROCEDURE_KEY, "procedure.name", "procedure.description", PROCEDURE_PATH,
                WorkerCapability.STANDARD, true, List.of());
    }

    private void write(String path, String source) throws Exception {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
