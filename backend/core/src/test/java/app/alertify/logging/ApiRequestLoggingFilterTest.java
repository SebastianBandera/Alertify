package app.alertify.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiRequestLoggingFilterTest {

    private final ApplicationEventLogger eventLogger = mock(ApplicationEventLogger.class);
    private final ApiRequestLoggingFilter filter = new ApiRequestLoggingFilter(eventLogger);

    @Test
    void filtersApiRequestAtRootContext() {
        MockHttpServletRequest request = request("", "/api/workers/status");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void filtersApiRequestBelowApplicationContext() {
        MockHttpServletRequest request = request("/alertify", "/api/workers/status");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void skipsNonApiRequestBelowApplicationContext() {
        MockHttpServletRequest request = request("/alertify", "/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void defersOnlySuccessfulUpgradesThatTransferredTheRequestContext() throws Exception {
        MockHttpServletRequest request = request("/alertify", "/api/admin/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            RequestLogContext context = (RequestLogContext) incoming.getAttribute(RequestLogContext.REQUEST_ATTRIBUTE);
            assertThat(context.requestId().toString()).isEqualTo(response.getHeader(ApiRequestLoggingFilter.REQUEST_ID_HEADER));
            assertThat(context.path()).isEqualTo("/alertify/api/admin/events");
            assertThat(context.method()).isEqualTo("GET");
            incoming.setAttribute(RequestLogContext.DEFERRED_ATTRIBUTE, Boolean.TRUE);
            response.setStatus(101);
        });

        verify(eventLogger, never()).success(any(), any());
        verify(eventLogger, never()).failure(any(), any(), any());
        assertThat(MDC.get(ApplicationEventLogger.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(ApplicationEventLogger.REQUEST_PATH_MDC_KEY)).isNull();
    }

    @Test
    void logsFailedUpgradesEvenWhenTheirMetadataWasTransferred() throws Exception {
        MockHttpServletRequest request = request("", "/api/viewer/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            incoming.setAttribute(RequestLogContext.DEFERRED_ATTRIBUTE, Boolean.TRUE);
            response.setStatus(403);
        });

        verify(eventLogger).failure(eq("API_REQUEST"), any(), org.mockito.ArgumentMatchers.argThat(data -> Integer.valueOf(403).equals(data.get("status"))));
    }

    @Test
    void normalHttpRequestsKeepTheirLog() throws Exception {
        MockHttpServletRequest request = request("/alertify", "/api/workers/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> response.setStatus(200));

        verify(eventLogger).success(eq("API_REQUEST"), org.mockito.ArgumentMatchers.argThat(data -> Integer.valueOf(200).equals(data.get("status"))));
    }

    private static MockHttpServletRequest request(String contextPath, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setContextPath(contextPath);
        request.setServletPath(servletPath);
        request.setRequestURI(contextPath + servletPath);
        return request;
    }
}
