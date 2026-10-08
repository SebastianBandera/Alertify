package app.alertify.realtime;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import app.alertify.logging.RequestLogContext;

/** Transfers only safe HTTP metadata; no credentials are retained. */
final class EventHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest))
            return false;

        Object context = servletRequest.getServletRequest().getAttribute(RequestLogContext.REQUEST_ATTRIBUTE);
        if (!(context instanceof RequestLogContext))
            return false;

        attributes.put(RequestLogContext.REQUEST_ATTRIBUTE, context);
        servletRequest.getServletRequest().setAttribute(RequestLogContext.DEFERRED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        // Failed upgrades are logged by the HTTP request filter.
    }
}
