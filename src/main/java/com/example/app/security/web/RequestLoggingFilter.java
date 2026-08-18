package com.example.app.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs one structured line per HTTP request: method, path, status, duration and the
 * authenticated user id.
 *
 * <p>Registered <em>inside</em> the security filter chain (after
 * {@code SecurityContextHolderFilter}): the inner chain has committed the final response
 * status (including 401/403 from the authentication/authorization filters) by the time the
 * line is logged, while the {@code SecurityContext} is still populated, so the authenticated
 * user id resolves correctly. Logging happens in {@code finally}, so a line is emitted even
 * when the chain throws.
 *
 * <p>Privacy: only metadata is logged - the raw path without query string, never headers,
 * cookies or request/response bodies (login requests carry passwords).
 *
 * <p>Not a Spring bean: it is constructed in {@code SecurityConfig} and added to the chain
 * exactly once, avoiding duplicate servlet registration.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Liveness/readiness probes would spam the log on every scrape.
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getName()))
                    ? authentication.getName()
                    : null;
            log.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", durationMs)
                    .addKeyValue("user_id", userId)
                    .log("request completed");
        }
    }
}