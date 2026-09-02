package app.alertify.logging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds a request ID to every API response and records request duration, status
 * and a small allow-list of safe query parameters without logging request
 * bodies or arbitrary parameters that may contain sensitive data.
 */
public final class ApiRequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String ERROR_CODE_REQUEST_ATTRIBUTE = ApiRequestLoggingFilter.class.getName() + ".errorCode";
    private static final Set<String> SAFE_QUERY_PARAMETERS = Set.of(
            "page", "size", "sort", "tagId", "tagOperator", "name", "user", "subject",
            "event", "level", "outcome", "date", "eventAt", "path"
    );

    private final ApplicationEventLogger eventLogger;

    public ApiRequestLoggingFilter(ApplicationEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        long startedAt = System.nanoTime();
        response.setHeader(REQUEST_ID_HEADER, requestId.toString());
        MDC.put(ApplicationEventLogger.REQUEST_ID_MDC_KEY, requestId.toString());
        MDC.put(ApplicationEventLogger.REQUEST_PATH_MDC_KEY, request.getRequestURI());

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            eventLogger.error("API_UNHANDLED_ERROR", Map.of("method", request.getMethod(), "path", request.getRequestURI(), "errorType", exception.getClass().getSimpleName()));
            throw exception;
        } finally {
            Map<String, Object> data = requestData(request);
            data.put("status", response.getStatus());
            data.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

            int status = response.getStatus();
            String errorCode = errorCode(request);
            ApplicationLogLevel level = ApiResponseLogLevelResolver.resolve(status, errorCode);

            if (status >= 400) {
                eventLogger.failure("API_REQUEST", level, data);
            } else {
                eventLogger.success("API_REQUEST", data);
            }
            MDC.remove(ApplicationEventLogger.REQUEST_ID_MDC_KEY);
            MDC.remove(ApplicationEventLogger.REQUEST_PATH_MDC_KEY);
        }
    }

    private static String errorCode(HttpServletRequest request) {
        Object value = request.getAttribute(ERROR_CODE_REQUEST_ATTRIBUTE);
        return value instanceof String code ? code : null;
    }

    private static Map<String, Object> requestData(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", request.getMethod());
        data.put("path", request.getRequestURI());

        Map<String, Object> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (SAFE_QUERY_PARAMETERS.contains(name)) {
                query.put(name, values.length == 1 ? values[0] : values);
            }
        });
        if (!query.isEmpty())
            data.put("query", query);

        return data;
    }
}
