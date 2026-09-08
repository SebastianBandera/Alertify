package app.alertify.hooks.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import app.alertify.hooks.HookInvocationRejectedException;
import app.alertify.hooks.model.Hook;
import org.springframework.http.HttpStatus;

@Service
public class HookAdmissionService {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> ADMIT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local lease = tonumber(ARGV[2])
            local maximum = tonumber(ARGV[3])
            local rate = tonumber(ARGV[4])
            local window = tonumber(ARGV[5])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if rate > 0 then redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - window) end
            if maximum > 0 and redis.call('ZCARD', KEYS[1]) >= maximum then return 1 end
            if rate > 0 and redis.call('ZCARD', KEYS[2]) >= rate then return 2 end
            if maximum > 0 then
                redis.call('ZADD', KEYS[1], now + lease, ARGV[6])
                redis.call('PEXPIRE', KEYS[1], lease * 2)
            end
            if rate > 0 then
                redis.call('ZADD', KEYS[2], now, ARGV[6])
                redis.call('PEXPIRE', KEYS[2], window * 2)
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
                redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
                redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) * 2)
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public HookAdmissionService(StringRedisTemplate redis) { this.redis = redis; }

    public boolean limited(Hook hook) { return hook.getMaxConcurrentInvocations() != null || hook.getRateLimitCount() != null; }

    public void admit(Hook hook, UUID invocationId) {
        if (!limited(hook))
            return;

        long now = Instant.now().toEpochMilli();
        long window = hook.getRateLimitWindowSeconds() == null ? 0 : Duration.ofSeconds(hook.getRateLimitWindowSeconds()).toMillis();
        try {
            Long result = redis.execute(ADMIT, keys(hook.getId()), Long.toString(now), Long.toString(LEASE_DURATION.toMillis()),
                    Integer.toString(hook.getMaxConcurrentInvocations() == null ? 0 : hook.getMaxConcurrentInvocations()),
                    Integer.toString(hook.getRateLimitCount() == null ? 0 : hook.getRateLimitCount()), Long.toString(window), invocationId.toString());
            if (result == null)
                throw unavailable();

            if (result == 1)
                throw new HookInvocationRejectedException(HttpStatus.TOO_MANY_REQUESTS, "HOOK_CONCURRENCY_LIMIT", "The hook has reached its concurrent invocation limit");

            if (result == 2)
                throw new HookInvocationRejectedException(HttpStatus.TOO_MANY_REQUESTS, "HOOK_RATE_LIMIT", "The hook rate limit has been reached");
        } catch (HookInvocationRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public void renew(long hookId, UUID invocationId) {
        try {
            redis.execute(RENEW, List.of(concurrentKey(hookId)), invocationId.toString(), Long.toString(Instant.now().toEpochMilli()), Long.toString(LEASE_DURATION.toMillis()));
        } catch (RuntimeException ignored) {
            // The lease remains bounded and will expire. A later renewal may recover.
        }
    }

    public void release(long hookId, UUID invocationId) {
        try {
            redis.opsForZSet().remove(concurrentKey(hookId), invocationId.toString());
        } catch (RuntimeException ignored) {
            // The timestamp score guarantees eventual expiration after a Redis outage.
        }
    }

    public void rollback(long hookId, UUID invocationId) {
        try {
            redis.opsForZSet().remove(concurrentKey(hookId), invocationId.toString());
            redis.opsForZSet().remove(rateKey(hookId), invocationId.toString());
        } catch (RuntimeException ignored) {
            // Both entries have bounded TTLs; this is only a best-effort rollback after persistence failed.
        }
    }

    public Duration renewalInterval() { return LEASE_DURATION.dividedBy(3); }
    private static List<String> keys(long hookId) { return List.of(concurrentKey(hookId), rateKey(hookId)); }
    private static String concurrentKey(long hookId) { return "alertify:hooks:{" + hookId + "}:leases"; }
    private static String rateKey(long hookId) { return "alertify:hooks:{" + hookId + "}:rate"; }
    private static HookInvocationRejectedException unavailable() { return new HookInvocationRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "HOOK_LIMIT_UNAVAILABLE", "Hook admission limits are temporarily unavailable"); }
}
