package com.example.app.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration surface for the security module's cross-cutting HTTP concerns:
 * CORS, per-IP throttling (burst layer) and per-IP rate limiting (quota layer).
 *
 * <p>Defaults live in {@code application.yml} under {@code app.security.*} (the
 * yml files are the config surface); this record carries no defaults of its own.
 * Registered via {@code @EnableConfigurationProperties} on {@link SecurityConfig}.
 *
 * @param cors      CORS settings for browser clients
 * @param throttle  burst layer: short-window per-IP limit
 * @param rateLimit quota layer: sustained per-IP limit
 * @param limiter   Redis-backed limiter behavior (fail-open on Redis outage)
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityRateLimitProperties(
        Cors cors,
        Throttle throttle,
        RateLimit rateLimit,
        Limiter limiter) {

    /** CORS: allowed browser origins (no credentials — JWT bearer, no cookies). */
    public record Cors(List<String> allowedOrigins) {
    }

    /** Burst layer: {@code limitForPeriod} permits per {@code limitRefreshPeriod}. */
    public record Throttle(boolean enabled, int limitForPeriod, Duration limitRefreshPeriod) {
    }

    /** Quota layer: {@code limitForPeriod} permits per {@code limitRefreshPeriod}. */
    public record RateLimit(boolean enabled, int limitForPeriod, Duration limitRefreshPeriod) {
    }

    /**
     * Redis-backed limiter behavior: when {@code redisFailOpen} is true (default),
     * a Redis outage logs + counts the failure and ALLOWS the request (availability
     * over strict limiting); when false, the failure propagates (fail closed, 500).
     */
    public record Limiter(boolean redisFailOpen) {
    }
}