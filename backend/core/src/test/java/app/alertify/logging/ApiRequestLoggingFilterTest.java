package app.alertify.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiRequestLoggingFilterTest {

    private final ApiRequestLoggingFilter filter = new ApiRequestLoggingFilter(mock(ApplicationEventLogger.class));

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

    private static MockHttpServletRequest request(String contextPath, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath(contextPath);
        request.setServletPath(servletPath);
        request.setRequestURI(contextPath + servletPath);
        return request;
    }
}
