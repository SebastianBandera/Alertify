package app.alertify.ai.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.worker.contract.WorkerCapability;

class AiTemplateSourceServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final AlertTemplateDefinitionRepository alertTemplates = mock(AlertTemplateDefinitionRepository.class);
    private final ProcedureTemplateDefinitionRepository procedureTemplates = mock(ProcedureTemplateDefinitionRepository.class);
    private AiTemplateSourceService service;

    @BeforeEach
    void setUp() {
        service = new AiTemplateSourceService(alertTemplates, procedureTemplates, temporaryDirectory.toString());
    }

    @Test
    void returnsAnAlertTemplateSourceSelectedByItsRegisteredKey() throws IOException {
        String templateKey = "app.alertify.alerts.templates.SampleAlertTemplate";
        String sourcePath = "app/alertify/alerts/templates/SampleAlertTemplate.java";
        write(sourcePath, "package app.alertify.alerts.templates;\npublic class SampleAlertTemplate {}\n");
        when(alertTemplates.findByTemplateKey(templateKey)).thenReturn(Optional.of(new AlertTemplateDefinition(
                templateKey, "sample.name", "sample.description", sourcePath, WorkerCapability.STANDARD)));

        AiTemplateSource result = service.alert(templateKey);

        assertThat(result.templateType()).isEqualTo("ALERT");
        assertThat(result.templateKey()).isEqualTo(templateKey);
        assertThat(result.path()).isEqualTo("backend/alert-templates/src/main/java/" + sourcePath);
        assertThat(result.content()).contains("class SampleAlertTemplate");
    }

    @Test
    void returnsAProcedureTemplateSourceSelectedByItsRegisteredKey() throws IOException {
        String templateKey = "app.alertify.procedures.templates.SampleProcedureTemplate";
        String sourcePath = "app/alertify/procedures/templates/SampleProcedureTemplate.java";
        write(sourcePath, "package app.alertify.procedures.templates;\npublic class SampleProcedureTemplate {}\n");
        when(procedureTemplates.findByTemplateKey(templateKey)).thenReturn(Optional.of(
                new ProcedureTemplateDefinition(templateKey, "sample.name", "sample.description", sourcePath,
                        WorkerCapability.STANDARD, false, List.of())));

        AiTemplateSource result = service.procedure(templateKey);

        assertThat(result.templateType()).isEqualTo("PROCEDURE");
        assertThat(result.templateKey()).isEqualTo(templateKey);
        assertThat(result.path()).isEqualTo("backend/alert-templates/src/main/java/" + sourcePath);
        assertThat(result.content()).contains("class SampleProcedureTemplate");
    }

    @Test
    void rejectsARegisteredPathThatEscapesTheSourceRoot() {
        String templateKey = "app.alertify.alerts.templates.OutsideTemplate";
        when(alertTemplates.findByTemplateKey(templateKey)).thenReturn(Optional.of(new AlertTemplateDefinition(
                templateKey, "outside.name", "outside.description", "../OutsideTemplate.java",
                WorkerCapability.STANDARD)));

        assertThatThrownBy(() -> service.alert(templateKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Registered template source path is invalid");
    }

    @Test
    void rejectsUnknownKeysWithoutUsingThemAsPaths() {
        String templateKey = "../../secrets";
        when(alertTemplates.findByTemplateKey(templateKey)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.alert(templateKey))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Alert template not found: ../../secrets");
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
