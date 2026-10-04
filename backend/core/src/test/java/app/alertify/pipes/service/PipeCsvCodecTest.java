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
import app.alertify.pipes.model.PipeStepPhase;
import app.alertify.procedures.model.Procedure;
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
}
