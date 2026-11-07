package app.alertify.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import app.alertify.grpc.api.WorkerResourceUsageResponse;
import app.alertify.system.BackendStatusService;
import app.alertify.system.SystemStatusService;
import app.alertify.system.api.BackendStatusResponse;

class SystemStatusControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private BackendStatusService backend;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        backend = context.getBean(BackendStatusService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void returnsAnUncachedSnapshotWithoutQueryingWorkerStatus() throws Exception {
        authenticate("ADMIN");
        when(backend.status()).thenReturn(new BackendStatusResponse("backend-node", UUID.randomUUID(), Instant.EPOCH,
                12_000L, Instant.now(), new WorkerResourceUsageResponse(10L, 100L, 5L, 50L, null, 2)));

        mvc.perform(get("/api/system-status/backend"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.name").value("backend-node"))
                .andExpect(jsonPath("$.uptimeMillis").value(12_000))
                .andExpect(jsonPath("$.resourceUsage.memoryUsedBytes").value(10))
                .andExpect(jsonPath("$.resourceUsage.availableProcessors").value(2));
        verifyNoInteractions(context.getBean(SystemStatusService.class));
    }

    @Test
    void requiresAnAdministrator() {
        authenticate("DASHBOARD");
        assertThatThrownBy(() -> context.getBean(SystemStatusController.class).backend()).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(backend);
    }

    @Test
    void requiresAuthentication() {
        assertThatThrownBy(() -> context.getBean(SystemStatusController.class).backend()).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(backend);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean
        BackendStatusService backend() {
            return mock(BackendStatusService.class);
        }

        @Bean
        SystemStatusService system() {
            return mock(SystemStatusService.class);
        }

        @Bean
        SystemStatusController controller(SystemStatusService system, BackendStatusService backend) {
            return new SystemStatusController(system, backend);
        }
    }
}
