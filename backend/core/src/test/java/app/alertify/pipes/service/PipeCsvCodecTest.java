package app.alertify.pipes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidPipeImportException;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepBinding;
import app.alertify.pipes.model.PipeStepPhase;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import tools.jackson.databind.json.JsonMapper;

class PipeCsvCodecTest {
    private final PipeCsvCodec codec = new PipeCsvCodec(JsonMapper.builder().build());

    @Test
    void preservesFinallyPhaseAndIndependentBudgetInExportAndImport() {
        Pipe pipe = new Pipe("cleanup", null, false, false);
        pipe.setFinallyTimeoutMillis(12_000);
        Procedure procedure = mock(Procedure.class);
        when(procedure.getName()).thenReturn("cleanup procedure");
        PipeStep step = PipeStep.procedure(pipe, "cleanup", 0, procedure, 5_000, List.of("SUCCESS"));
        step.setPhase(PipeStepPhase.FINALLY);
        pipe.replaceSteps(List.of(step));
        var row = codec.read(codec.write(List.of(pipe))).getFirst();
        assertThat(row.finallyTimeoutMillis()).isEqualTo(12_000);
        assertThat(row.steps().getFirst().phase()).isEqualTo(PipeStepPhase.FINALLY);
    }

    @Test
    void rejectsCsvWithoutFinallyTimeoutColumn() {
        String csv = "name,description,enabled,allowConcurrentExecutions,steps\nlegacy,,false,false,[]\n";
        assertThatThrownBy(() -> codec.read(csv.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPipeImportException.class)
                .hasMessage("CSV header must be exactly: name,description,enabled,allowConcurrentExecutions,steps,finallyTimeoutMillis");
    }

    @Test
    void preservesResultPointerBindingInExportAndImport() {
        Pipe pipe = new Pipe("result binding", null, false, false);
        Procedure producerProcedure = mock(Procedure.class);
        when(producerProcedure.getName()).thenReturn("producer procedure");
        PipeStep producer = PipeStep.procedure(pipe, "producer", 0, producerProcedure, 5_000, List.of("SUCCESS"));
        Procedure consumerProcedure = mock(Procedure.class);
        when(consumerProcedure.getName()).thenReturn("consumer procedure");
        PipeStep consumer = PipeStep.procedure(pipe, "consumer", 1, consumerProcedure, 5_000, List.of("SUCCESS"));
        var parameter = mock(ProcedureTemplateParameterDefinition.class);
        when(parameter.getParameterKey()).thenReturn("pin");
        consumer.addBinding(new PipeStepBinding(consumer, parameter, producer, "/code"));
        pipe.replaceSteps(List.of(producer, consumer));

        var row = codec.read(codec.write(List.of(pipe))).getFirst();
        var binding = row.steps().get(1).bindings().getFirst();
        assertThat(binding.targetParameterKey()).isEqualTo("pin");
        assertThat(binding.sourceStepKey()).isEqualTo("producer");
        assertThat(binding.sourceOutputKey()).isNull();
        assertThat(binding.sourceResultPointer()).isEqualTo("/code");
    }
}
