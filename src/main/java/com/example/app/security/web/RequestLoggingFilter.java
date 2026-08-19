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
 * Logs one structured line per HTTP request with the standard access-log fields:
 *
 * <pre>
 * http.request.method      request method
 * url.path                 raw request URI without the query string
 * http.response.status_code final response status
 * duration_ms              processing time in milliseconds
 * user.id                  authenticated subject, null for anonymous requests
 * event.outcome            "success" (status &lt; 400, no exception) or "failure"
 * error.type               simple class name when an exception propagated through the
 *                          filter (unhandled failures); absent otherwise
 * </pre>
 *
 * <p>Field names follow the ECS conventions so the structured log lines are directly
 * consumable by standard log pipelines. Registered <em>inside</em> the security filter chain
 * (after {@code SecurityContextHolderFilter}): the inner chain has committed the final
 * response status (including 401/403 from the authentication/authorization filters) by the
 * time the line is logged, while the {@code SecurityContext} is still populated, so the
 * authenticated user id resolves correctly. Logging happens in {@code finally}, so a line is
 * emitted even when the chain throws - the exception is captured for {@code error.type} and
 * always rethrown, never swallowed.
 *
 * <p>Privacy: only metadata is logged - never headers, cookies or request/response bodies
 * (login requests carry passwords). Exception details are limited to the type name; messages
 * and stack traces are not logged here.
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
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable t) {
            // Capture for error.type/event.outcome; always rethrow - nothing is swallowed.
            failure = t;
            throw t;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String userId = (authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getName()))
                    ? authentication.getName()
                    : null;
            int status = response.getStatus();
            String outcome = (failure != null || status >= 400) ? "failure" : "success";

            var logSpec = log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .addKeyValue("http.response.status_code", status)
                    .addKeyValue("duration_ms", durationMs)
                    .addKeyValue("user.id", userId)
                    .addKeyValue("event.outcome", outcome);
            if (failure != null) {
                logSpec = logSpec.addKeyValue("error.type", failure.getClass().getSimpleName());
            }
            logSpec.log("request completed");
        }
    }
}