package com.example.app.security.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the request log contract: the standard ECS-aligned fields, the
 * success/failure outcome, and error capture on unhandled exceptions.
 */
class RequestLoggingFilterTest {

    private static final ListAppender<ILoggingEvent> APPENDER = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        if (!logger.isAttached(APPENDER)) {
            logger.addAppender(APPENDER);
            APPENDER.start();
        }
        APPENDER.list.clear();
        SecurityContextHolder.clearContext();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(HashMap::new, (map, kvp) -> map.put(kvp.key, kvp.value), HashMap::putAll);
    }

    @Test
    void successRequestLogsAllStandardFields() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-1", null, List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        new RequestLoggingFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/v1/activities"),
                response, new MockFilterChain());

        Map<String, Object> kv = keyValues(APPENDER.list.get(0));
        assertThat(kv.get("http.request.method")).isEqualTo("GET");
        assertThat(kv.get("url.path")).isEqualTo("/api/v1/activities");
        assertThat(kv.get("http.response.status_code")).isEqualTo(200);
        assertThat(kv.get("duration_ms")).isInstanceOf(Number.class);
        assertThat(kv.get("user.id")).isEqualTo("user-1");
        assertThat(kv.get("event.outcome")).isEqualTo("success");
        assertThat(kv).doesNotContainKey("error.type");
    }

    @Test
    void clientErrorIsAFailureOutcome() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        new RequestLoggingFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/v1/activities/not-found"),
                response, new MockFilterChain());

        assertThat(keyValues(APPENDER.list.get(0)).get("event.outcome")).isEqualTo("failure");
    }

    @Test
    void unhandledExceptionIsCapturedForErrorTypeAndRethrown() {
        MockFilterChain throwingChain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/v1/activities"),
                new MockHttpServletResponse(), throwingChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        Map<String, Object> kv = keyValues(APPENDER.list.get(0));
        assertThat(kv.get("event.outcome")).isEqualTo("failure");
        assertThat(kv.get("error.type")).isEqualTo("IllegalStateException");
    }

    @Test
    void anonymousRequestLogsNullUserId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        new RequestLoggingFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/v1/activities"),
                response, new MockFilterChain());

        Map<String, Object> kv = keyValues(APPENDER.list.get(0));
        assertThat(kv.get("user.id")).isNull();
        assertThat(kv.get("event.outcome")).isEqualTo("failure");
    }
}
