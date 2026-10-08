package app.alertify.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import app.alertify.logging.RequestLogContext;

class EventHandshakeInterceptorTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/events", "/alertify/api/viewer/events"})
    void transfersOnlyTheOriginalSafeMetadata(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        RequestLogContext context = new RequestLogContext(UUID.randomUUID(), path, "GET", System.nanoTime());
        request.setAttribute(RequestLogContext.REQUEST_ATTRIBUTE, context);
        request.addHeader("Authorization", "test-only-credential");
        Map<String, Object> attributes = new HashMap<>();

        assertThat(new EventHandshakeInterceptor().beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes)).isTrue();

        assertThat(attributes).containsOnlyKeys(RequestLogContext.REQUEST_ATTRIBUTE);
        assertThat(attributes.get(RequestLogContext.REQUEST_ATTRIBUTE)).isSameAs(context);
        assertThat(request.getAttribute(RequestLogContext.DEFERRED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(attributes.toString()).doesNotContain("test-only-credential");
    }
}
