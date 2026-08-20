package com.example.app.security.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Distributed per-IP fixed-window rate limiter backed by Redis.
 *
 * <p>Each window is a counter key {@code app:limit:{layer}:{ip}} incremented
 * atomically with a Lua script ({@code INCR} + {@code EXPIRE} on first
 * increment); the key expires with the window, so no idle-eviction sweep is
 * needed and limits hold across instances (unlike the removed in-memory
 * {@code PerIpRateLimiterRegistry}).
 *
 * <p>Fails OPEN on a Redis outage (availability over strict limiting): a
 * connection failure or command timeout ({@code RedisSystemException}, raised
 * by the configured {@code spring.data.redis.timeout} when Redis is slow or
 * hung) logs a WARN, increments the {@code app.security.limiter.failopen}
 * counter and ALLOWS the request. When {@code redisFailOpen} is false the
 * exception propagates (fail closed).
 *
 * @param layer           layer name ({@code "throttle"} / {@code "rate-limit"}), part of the key
 * @param limit           max requests per {@code window} per IP
 * @param window          fixed window duration (also the key TTL)
 * @param redisFailOpen   allow requests when Redis is unavailable
 */
public class RedisFixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);

    private static final String KEY_PREFIX = "app:limit:";
    private static final DefaultRedisScript<Long> INCR_AND_EXPIRE = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);

    private final String layer;
    private final int limit;
    private final Duration window;
    private final boolean redisFailOpen;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RedisFixedWindowRateLimiter(String layer, int limit, Duration window,
                                       boolean redisFailOpen, StringRedisTemplate redisTemplate,
                                       MeterRegistry meterRegistry) {
        this.layer = layer;
        this.limit = limit;
        this.window = window;
        this.redisFailOpen = redisFailOpen;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return {@code true} when the request may pass, {@code false} when it is
     * over the limit for this window.
     */
    public boolean allow(String ip) {
        String key = KEY_PREFIX + layer + ":" + ip;
        Long count;
        try {
            // ARGV is serialized by the StringRedisSerializer - pass the window as a String.
            count = redisTemplate.execute(INCR_AND_EXPIRE, List.of(key), String.valueOf(window.toSeconds()));
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            if (redisFailOpen) {
                log.warn("Redis unavailable for limiter layer {} - failing open (request allowed): {}",
                        layer, e.toString());
                meterRegistry.counter("app.security.limiter.failopen", "layer", layer).increment();
                return true;
            }
            throw e;
        }
        // count is null only if the script returned nothing - treat as over-limit? No:
        // a null result means Redis did not execute (should not happen); fail closed.
        return count == null || count <= limit;
    }
}