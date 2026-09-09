package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import app.alertify.procedures.ProcedureBusyException;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.ProcedureInvocationFailure;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import io.grpc.Deadline;

class ProcedureHandleFactoryTest {

    @Test
    void mapsBusyInvocationFailureToTypedException() {
        ProcedureInvoker invoker = mock(ProcedureInvoker.class);
        Deadline deadline = Deadline.after(1, TimeUnit.MINUTES);
        when(invoker.invoke("signed-token", deadline)).thenReturn(InvokeProcedureResponse.newBuilder()
                .setFailure(ProcedureInvocationFailure.newBuilder()
                        .setKind(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_BUSY)
                        .setMessage("Procedure is busy"))
                .build());
        AlertParameter parameter = AlertParameter.newBuilder().setName("totp")
                .setProcedureId(7L).setInvocationToken("signed-token").build();

        var procedure = new ProcedureHandleFactory(invoker).create(parameter, deadline);

        assertThatThrownBy(procedure::execute).isInstanceOf(ProcedureBusyException.class)
                .hasMessage("Procedure is busy");
    }
}
