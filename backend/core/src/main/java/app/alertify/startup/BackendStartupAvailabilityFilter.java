package app.alertify.startup;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Returns a stable 503 for API traffic received before backend readiness. */
public final class BackendStartupAvailabilityFilter extends OncePerRequestFilter {

    private final BackendStartupAvailability availability;

    public BackendStartupAvailabilityFilter(BackendStartupAvailability availability) {
        this.availability = availability;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (availability.isReady()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":503,\"code\":\"STARTUP_NOT_READY\",\"message\":\"The backend is still initializing\",\"fieldErrors\":{},\"parameters\":{}}");
    }
}
