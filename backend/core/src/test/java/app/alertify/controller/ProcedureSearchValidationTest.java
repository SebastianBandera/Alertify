package app.alertify.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import app.alertify.api.error.ApiExceptionHandler;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.procedures.service.ProcedureCsvService;
import app.alertify.procedures.service.ProcedureManagementService;
import jakarta.validation.Validation;

class ProcedureSearchValidationTest {
    private final ProcedureManagementService service = mock(ProcedureManagementService.class);
    private MockMvc mvc;

    @BeforeEach
    void initializeMvc() {
        var controller = new ProcedureController(service, mock(ProcedureCatalogService.class), mock(ProcedureCsvService.class));
        var proxy = new ProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor(Validation.buildDefaultValidatorFactory().getValidator()));
        mvc = MockMvcBuilders.standaloneSetup(proxy.getProxy())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler(mock(ApplicationEventLogger.class))).build();
    }

    @Test
    void rejectsZeroTemplateAsAClientValidationErrorWithoutQuerying() throws Exception {
        mvc.perform(get("/api/procedures").param("templateId", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request parameters are invalid"));
        verifyNoInteractions(service);
    }

    @Test
    void positiveTemplateReachesTheSearchUnchanged() throws Exception {
        when(service.search(eq(null), eq(9L), eq(Set.of()), eq(false), any(Pageable.class))).thenReturn(Page.empty(PageRequest.of(0, 20)));
        mvc.perform(get("/api/procedures").param("templateId", "9")).andExpect(status().isOk());
        verify(service).search(eq(null), eq(9L), eq(Set.of()), eq(false), any(Pageable.class));
    }
}
