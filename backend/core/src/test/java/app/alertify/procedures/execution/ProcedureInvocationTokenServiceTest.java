package app.alertify.procedures.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.procedures.ProcedureDepthExceededException;
import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.worker.grpc.ProcedureParentKind;

class ProcedureInvocationTokenServiceTest {

    @Test
    void validatesAParentBoundCapabilityWhileTheParentIsActive() {
        ProcedureInvocationRegistry registry = new ProcedureInvocationRegistry();
        ProcedureInvocationTokenService service = new ProcedureInvocationTokenService(registry, properties());
        UUID root = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(30);
        registry.register(parent, deadline);

        ProcedureInvocationTokenService.Claims claims = service.validate(service.issue(
                42, root, parent, ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT, 1, deadline
        ));

        assertThat(claims.procedureId()).isEqualTo(42);
        assertThat(claims.rootExecutionId()).isEqualTo(root);
        assertThat(claims.parentExecutionId()).isEqualTo(parent);
        assertThat(claims.depth()).isEqualTo(1);
    }

    @Test
    void rejectsAHandleAfterItsParentHasFinished() {
        ProcedureInvocationRegistry registry = new ProcedureInvocationRegistry();
        ProcedureInvocationTokenService service = new ProcedureInvocationTokenService(registry, properties());
        UUID parent = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(30);
        String token = service.issue(42, parent, parent,
                ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT, 1, deadline);

        assertThatThrownBy(() -> service.validate(token))
                .isInstanceOf(ProcedureExecutionException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    void limitsRecursiveChainsAtDepthSixteen() {
        ProcedureInvocationRegistry registry = new ProcedureInvocationRegistry();
        ProcedureInvocationTokenService service = new ProcedureInvocationTokenService(registry, properties());
        UUID parent = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(30);
        registry.register(parent, deadline);

        String token = service.issue(42, parent, parent,
                ProcedureParentKind.PROCEDURE_PARENT_KIND_PROCEDURE, 17, deadline);

        assertThatThrownBy(() -> service.validate(token))
                .isInstanceOf(ProcedureDepthExceededException.class)
                .hasMessageContaining("16");
    }

    private static ProcedureExecutionProperties properties() {
        return new ProcedureExecutionProperties(16);
    }
}
