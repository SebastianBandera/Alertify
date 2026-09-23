package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DashboardRunRateLimiterTest {

    private static final String KEY = "alertify:dashboard-run:{viewer}";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private DashboardRunRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        limiter = new DashboardRunRateLimiter(redis);
    }

    @Test
    void takesTheViewersSlotForOneSecond() {
        when(values.setIfAbsent(KEY, "1", DashboardRunRateLimiter.WINDOW)).thenReturn(true);

        assertThatCode(() -> limiter.acquire("viewer")).doesNotThrowAnyException();

        verify(values).setIfAbsent(KEY, "1", DashboardRunRateLimiter.WINDOW);
    }

    @Test
    void rejectsASecondRunWithinTheSameSecond() {
        when(values.setIfAbsent(KEY, "1", DashboardRunRateLimiter.WINDOW)).thenReturn(false);

        assertThatThrownBy(() -> limiter.acquire("viewer"))
                .isInstanceOfSatisfying(DashboardRunRejectedException.class, exception -> {
                    Assertions.assertThat(exception.getStatus().value()).isEqualTo(429);
                    Assertions.assertThat(exception.getCode()).isEqualTo("DASHBOARD_RUN_RATE_LIMIT");
                });
    }

    @Test
    void refusesTheRunWhenRedisIsUnavailable() {
        when(values.setIfAbsent(KEY, "1", DashboardRunRateLimiter.WINDOW)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> limiter.acquire("viewer"))
                .isInstanceOfSatisfying(DashboardRunRejectedException.class, exception -> {
                    Assertions.assertThat(exception.getStatus().value()).isEqualTo(503);
                    Assertions.assertThat(exception.getCode()).isEqualTo("DASHBOARD_RUN_LIMIT_UNAVAILABLE");
                });
    }
}
