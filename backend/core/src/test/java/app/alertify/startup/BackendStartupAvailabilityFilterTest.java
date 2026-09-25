package app.alertify.startup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BackendStartupAvailabilityFilterTest {

    @Test
    void rejectsApiTrafficUntilStartupIsReady() throws Exception {
        BackendStartupAvailability availability = new BackendStartupAvailability();
        BackendStartupAvailabilityFilter filter = new BackendStartupAvailabilityFilter(availability);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secrets");
        request.setServletPath("/api/secrets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("STARTUP_NOT_READY");
    }

    @Test
    void allowsApiTrafficAfterStartupIsReady() throws Exception {
        BackendStartupAvailability availability = new BackendStartupAvailability();
        availability.ready();
        BackendStartupAvailabilityFilter filter = new BackendStartupAvailabilityFilter(availability);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secrets");
        request.setServletPath("/api/secrets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
