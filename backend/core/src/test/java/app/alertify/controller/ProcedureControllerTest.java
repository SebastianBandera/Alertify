package app.alertify.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

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
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import app.alertify.api.error.ApiExceptionHandler;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.procedures.service.ProcedureCsvService;
import app.alertify.procedures.service.ProcedureManagementService;

class ProcedureControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private ProcedureManagementService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        service = context.getBean(ProcedureManagementService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        authenticate("ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void returnsTheRequestedProcedureById() throws Exception {
        when(service.get(42L)).thenReturn(new ProcedureResponse(42L, 1, 7L, "example.Template", "template.name",
                "Selected procedure", "", "-", true, false, Set.of(), List.of(), null, null));

        mvc.perform(get("/api/procedures/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Selected procedure"));
        verify(service).get(42L);
    }

    @Test
    void returnsNotFoundForADeletedProcedure() throws Exception {
        when(service.get(42L)).thenThrow(new ResourceNotFoundException("Procedure not found"));
        mvc.perform(get("/api/procedures/42")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonPositiveIdentifiers() throws Exception {
        mvc.perform(get("/api/procedures/0")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsAUserWithoutTheAdministratorRole() {
        authenticate("DASHBOARD");
        assertThatThrownBy(() -> context.getBean(ProcedureController.class).get(42L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void requiresAuthentication() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> context.getBean(ProcedureController.class).get(42L))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(service);
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-user", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean
        static MethodValidationPostProcessor validation() {
            return new MethodValidationPostProcessor();
        }

        @Bean
        ProcedureManagementService service() {
            return mock(ProcedureManagementService.class);
        }

        @Bean
        ProcedureController controller(ProcedureManagementService service) {
            return new ProcedureController(service, mock(ProcedureCatalogService.class), mock(ProcedureCsvService.class));
        }

        @Bean
        ApiExceptionHandler errors() {
            return new ApiExceptionHandler(mock(ApplicationEventLogger.class));
        }
    }
}
