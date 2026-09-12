package app.alertify.procedures.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.services.secret.SecretAccessService;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
class ProcedureExecutionPreparationServiceTest {

    @TempDir private Path sourceRoot;

    @Mock private ProcedureRepository procedureRepository;
    @Mock private ProcedureTemplateParameterDefinitionRepository definitionRepository;
    @Mock private ProcedureParameterValueRepository parameterValueRepository;
    @Mock private ConfigurationExpressionService configurationExpressionService;
    @Mock private SecretAccessService secretAccessService;

    @Test
    void preparesAConfiguredProcedureAsANonNullHandle() throws Exception {
        ProcedureTemplateDefinition template = new ProcedureTemplateDefinition(
                "dynamic.ParentProcedure", "name", "description", "ParentProcedure.java",
                WorkerCapability.STANDARD, false, List.of());
        ReflectionTestUtils.setField(template, "id", 2L);
        ProcedureTemplateParameterDefinition definition = new ProcedureTemplateParameterDefinition(
                template, "nested", "nested", "nested", Procedure.class.getName(),
                List.of(), true, null, false, 1, false, List.of(AlertParameterSource.PROCEDURE));
        ReflectionTestUtils.setField(definition, "id", 3L);
        app.alertify.procedures.model.Procedure owner = new app.alertify.procedures.model.Procedure(
                template, "Parent", null, "-", true, true, Set.of());
        ReflectionTestUtils.setField(owner, "id", 7L);
        app.alertify.procedures.model.Procedure referenced = new app.alertify.procedures.model.Procedure(
                template, "Nested", null, "-", true, true, Set.of());
        ReflectionTestUtils.setField(referenced, "id", 42L);
        ProcedureParameterValue configured = ProcedureParameterValue.procedure(owner, definition, referenced);
        Files.writeString(sourceRoot.resolve("ParentProcedure.java"), "source", StandardCharsets.UTF_8);

        when(procedureRepository.findById(7L)).thenReturn(Optional.of(owner));
        when(parameterValueRepository.findAllByOwnerIdOrdered(7L)).thenReturn(List.of(configured));
        when(definitionRepository.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(2L))
                .thenReturn(List.of(definition));

        PreparedProcedureExecution execution = service().prepare(7L, false);

        assertThat(execution.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.value()).isNull();
            assertThat(parameter.source()).isEqualTo(AlertParameterSource.PROCEDURE);
            assertThat(parameter.nullValue()).isFalse();
            assertThat(parameter.procedureId()).isEqualTo(42L);
        });
    }

    private ProcedureExecutionPreparationService service() {
        return new ProcedureExecutionPreparationService(
                procedureRepository, definitionRepository, parameterValueRepository,
                configurationExpressionService, secretAccessService,
                new WorkerGrpcProperties("worker", 9090, null, null,
                        new WorkerGrpcProperties.Execution(null, sourceRoot)));
    }
}
