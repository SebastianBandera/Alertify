package app.alertify.pipes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.jpa.repository.PipeExecutionRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateOutputDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeStep;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateDefinition;

class PipeTemplateMetadataTest {
    @Test
    void includesTheTemplateKeyForBothResourceTypesInOptionsAndDefinitions() {
        Alert alert = mock(Alert.class);
        var alertTemplate = mock(AlertTemplateDefinition.class);
        when(alert.getId()).thenReturn(11L);
        when(alert.getName()).thenReturn("HTTP check");
        when(alert.getTemplate()).thenReturn(alertTemplate);
        when(alertTemplate.getId()).thenReturn(1L);
        when(alertTemplate.getTemplateKey()).thenReturn("app.alertify.alerts.templates.WebRequestAlertTemplate");
        Procedure procedure = mock(Procedure.class);
        var procedureTemplate = mock(ProcedureTemplateDefinition.class);
        when(procedure.getId()).thenReturn(22L);
        when(procedure.getName()).thenReturn("JWT mutations");
        when(procedure.getTemplate()).thenReturn(procedureTemplate);
        when(procedureTemplate.getId()).thenReturn(2L);
        when(procedureTemplate.getTemplateKey()).thenReturn("app.alertify.procedures.templates.JwtNegativeTestTokensProcedureTemplate");
        var alerts = mock(AlertRepository.class);
        var procedures = mock(ProcedureRepository.class);
        when(alerts.findAll(Sort.by("name"))).thenReturn(List.of(alert));
        when(procedures.findAll(Sort.by("name"))).thenReturn(List.of(procedure));
        var service = new PipeManagementService(mock(PipeRepository.class), mock(PipeExecutionRepository.class),
                alerts, mock(AlertTemplateParameterDefinitionRepository.class), procedures,
                mock(ProcedureTemplateParameterDefinitionRepository.class), mock(ProcedureTemplateOutputDefinitionRepository.class),
                mock(HookRepository.class), mock(ProcedureParameterValueRepository.class), mock(TagRepository.class),
                mock(PipeExecutionOrchestrator.class), mock(ApplicationEventLogger.class), mock(PipeBindingExpressionService.class));
        var options = service.options().resources();
        assertThat(options).extracting(value -> value.templateKey()).containsExactly(
                alertTemplate.getTemplateKey(), procedureTemplate.getTemplateKey());

        Pipe pipe = new Pipe("metadata", null, false, false);
        pipe.replaceSteps(List.of(PipeStep.procedure(pipe, "mutate", 0, procedure, 30_000, List.of("SUCCESS")),
                PipeStep.alert(pipe, "check", 1, alert, 30_000, List.of("SUCCESS"))));
        assertThat(PipeMapper.response(pipe).steps()).extracting(value -> value.resourceTemplateKey())
                .containsExactly(procedureTemplate.getTemplateKey(), alertTemplate.getTemplateKey());
    }
}
