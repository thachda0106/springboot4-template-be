package com.example.app.security.web;

import com.example.app.shared.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/**
 * Sustained-quota layer: a longer-window per-IP limit (e.g. 100 req/min) that caps
 * total usage. Registered after the {@link ThrottleFilter} in the security filter
 * chain, so the burst layer is consumed first (shortest window fails fastest).
 *
 * <p>Uses the Redis fixed-window counter ({@link RedisFixedWindowRateLimiter},
 * atomic Lua INCR+EXPIRE) — the request thread is never blocked; over-quota
 * requests get an immediate 429 with the shared {@link ApiError} contract
 * ({@code code=RATE_LIMITED}) and an RFC 6585 {@code Retry-After} header. Limits
 * hold across instances; on a Redis outage the layer fails open (log + allow)
 * unless {@code app.security.limiter.redis-fail-open=false}.
 *
 * <p>Actuator endpoints are excluded so health probes and the Prometheus scraper
 * are never limited. CORS preflights never reach this filter: Spring Security's
 * {@code CorsFilter} short-circuits OPTIONS before it.
 *
 * <p>Not a Spring bean: constructed in {@code SecurityConfig} (only when the layer
 * is enabled) and added to the chain exactly once, like {@link RequestLoggingFilter}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String CODE = "RATE_LIMITED";
    private static final String MESSAGE = "Rate limit exceeded, please retry later";
    // jakarta.servlet-api defines no constant for 429 (RFC 6585).
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final RedisFixedWindowRateLimiter limiter;
    private final Duration refreshPeriod;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RedisFixedWindowRateLimiter limiter, Duration refreshPeriod,
                           ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.refreshPeriod = refreshPeriod;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Health probes and the Prometheus scraper must never be rate limited.
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Non-blocking: the Redis fixed-window counter is checked atomically (Lua INCR+EXPIRE).
        if (limiter.allow(request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rate limited request from {}: {} {}", request.getRemoteAddr(),
                request.getMethod(), request.getRequestURI());
        response.setStatus(HTTP_TOO_MANY_REQUESTS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(refreshPeriod.toSeconds()));
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(CODE, MESSAGE, request.getRequestURI()));
    }
}