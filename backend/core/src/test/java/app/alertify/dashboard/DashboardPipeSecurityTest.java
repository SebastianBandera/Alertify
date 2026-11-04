package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import app.alertify.controller.DashboardPipeController;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;

class DashboardPipeSecurityTest {
    @Test
    void alertRunPermissionDoesNotAuthorizePipeExecution() {
        var orchestrator = mock(PipeExecutionOrchestrator.class);
        var logger = mock(ApplicationEventLogger.class);
        var limiter = mock(DashboardRunRateLimiter.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Security.class, DashboardPipeController.class);
            context.registerBean(DashboardPipeCardService.class, () -> mock(DashboardPipeCardService.class));
            context.registerBean(ProcedureExecutionRepository.class, () -> mock(ProcedureExecutionRepository.class));
            context.registerBean(PipeExecutionOrchestrator.class, () -> orchestrator);
            context.registerBean(DashboardRunRateLimiter.class, () -> limiter);
            context.registerBean(ApplicationEventLogger.class, () -> logger);
            context.refresh();
            var controller = context.getBean(DashboardPipeController.class);
            for (String role : List.of("DASHBOARD_RUN", "ADMIN", "DASHBOARD")) {
                authenticate("DASHBOARD", role);
                assertThatThrownBy(() -> controller.run(7)).isInstanceOf(AccessDeniedException.class);
            }
            authenticate("DASHBOARD_PIPE_RUN");
            assertThatThrownBy(() -> controller.run(7)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(orchestrator, limiter);
            authenticate("DASHBOARD", "DASHBOARD_PIPE_RUN");
            when(logger.currentUsername()).thenReturn("operator");
            UUID id = UUID.randomUUID();
            when(orchestrator.triggerFromDashboard(7, "operator")).thenReturn(id);
            assertThat(controller.run(7).getBody().executionId()).isEqualTo(id);
            verify(limiter).acquire("operator");
        } finally { SecurityContextHolder.clearContext(); }
    }

    private static void authenticate(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("operator", "unused", java.util.Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security { }
}
