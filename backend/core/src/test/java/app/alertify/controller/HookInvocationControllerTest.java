package app.alertify.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import app.alertify.api.error.InvalidHookRequestException;
import app.alertify.hooks.api.HookAcceptedResponse;
import app.alertify.hooks.service.HookInvocationService;

class HookInvocationControllerTest {

    @Test
    void rejectsAnyRequestBodyWithoutInvokingTheService() {
        HookInvocationService service = mock(HookInvocationService.class);
        HookInvocationController controller = new HookInvocationController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.invoke(UUID.randomUUID(), null, request))
                .isInstanceOf(InvalidHookRequestException.class);
    }

    @Test
    void returnsAcceptedWithAStatusLocationForAnEmptyBody() throws Exception {
        UUID publicId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        HookInvocationService service = mock(HookInvocationService.class);
        when(service.invoke(publicId, "token")).thenReturn(new HookAcceptedResponse(invocationId));
        HookInvocationController controller = new HookInvocationController(service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/alertify/api/hooks/" + publicId + "/invoke");
        request.setContextPath("/alertify");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var response = controller.invoke(publicId, "token", request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getLocation().toString()).isEqualTo("http://localhost/alertify/api/hooks/" + publicId + "/invocations/" + invocationId);
            assertThat(response.getBody().invocationId()).isEqualTo(invocationId);
            verify(service).invoke(publicId, "token");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
