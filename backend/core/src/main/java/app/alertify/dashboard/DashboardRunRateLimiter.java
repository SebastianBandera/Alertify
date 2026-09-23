package app.alertify.dashboard;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Allows each dashboard viewer one on-demand alert run per second. The slot
 * lives in Redis so the limit holds across backend replicas; when Redis cannot
 * be reached the run is refused rather than let through unchecked.
 */
@Service
public class DashboardRunRateLimiter {

    static final Duration WINDOW = Duration.ofSeconds(1);

    private final StringRedisTemplate redis;

    public DashboardRunRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void acquire(String username) {
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key(username), "1", WINDOW);
        } catch (RuntimeException exception) {
            throw new DashboardRunRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "DASHBOARD_RUN_LIMIT_UNAVAILABLE", "Dashboard run limits are temporarily unavailable");
        }
        if (acquired == null)
            throw new DashboardRunRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "DASHBOARD_RUN_LIMIT_UNAVAILABLE", "Dashboard run limits are temporarily unavailable");

        if (!acquired)
            throw new DashboardRunRejectedException(HttpStatus.TOO_MANY_REQUESTS, "DASHBOARD_RUN_RATE_LIMIT", "Only one alert run per second is allowed from the dashboard");
    }

    private static String key(String username) { return "alertify:dashboard-run:{" + username + "}"; }
}
