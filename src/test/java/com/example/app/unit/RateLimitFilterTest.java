package com.example.app.unit;

import com.example.app.security.web.RateLimitFilter;
import com.example.app.security.web.RedisFixedWindowRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the quota layer ({@link RateLimitFilter}): per-IP behavior,
 * the 429 {@code ApiError} contract, {@code Retry-After}, and the actuator
 * exclusion. The limiter is backed by a mocked Redis template. No Spring
 * context, no Docker.
 */
class RateLimitFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RateLimitFilter filter(int limit, Duration refreshPeriod, Long... counts) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(counts[0], java.util.Arrays.copyOfRange(counts, 1, counts.length));
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter("rate-limit", limit,
                refreshPeriod, true, template, new SimpleMeterRegistry());
        return new RateLimitFilter(limiter, refreshPeriod, OBJECT_MAPPER);
    }

    private MockHttpServletRequest request(String ip, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void passesRequestsUpToTheQuota() throws Exception {
        RateLimitFilter filter = filter(3, Duration.ofMinutes(1), 1L, 2L, 3L);

        for (int i = 0; i < 3; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request("1.2.3.4", "/api/v1/activities"), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as("request %d passes", i + 1).isNotNull();
        }
    }

    @Test
    void rejectsTheRequestPastTheQuotaWithRateLimitedError() throws Exception {
        RateLimitFilter filter = filter(2, Duration.ofMinutes(1), 1L, 2L, 3L);

        for (int i = 0; i < 2; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request("1.2.3.4", "/api/v1/activities"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("1.2.3.4", "/api/v1/activities"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        String code = OBJECT_MAPPER.readTree(response.getContentAsString()).get("code").asText();
        assertThat(code).isEqualTo("RATE_LIMITED");
    }

    @Test
    void ipsAreLimitedIndependently() throws Exception {
        // IP 1.2.3.4 burns its budget (1, 2); IP 5.6.7.8 starts fresh (1).
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), 1L, 2L, 1L);

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        MockFilterChain rejectedChain = new MockFilterChain();
        filter.doFilter(request("1.2.3.4", "/api/v1/activities"), rejected, rejectedChain);
        filter.doFilter(request("1.2.3.4", "/api/v1/activities"), rejected, rejectedChain);
        assertThat(rejected.getStatus()).isEqualTo(429);

        MockFilterChain otherChain = new MockFilterChain();
        filter.doFilter(request("5.6.7.8", "/api/v1/activities"), new MockHttpServletResponse(), otherChain);
        assertThat(otherChain.getRequest()).isNotNull();
    }

    @Test
    void actuatorPathsPassThroughUnlimited() throws Exception {
        RateLimitFilter filter = filter(1, Duration.ofMinutes(1), 1L, 2L);

        MockHttpServletResponse burned = new MockHttpServletResponse();
        filter.doFilter(request("1.2.3.4", "/api/v1/activities"), burned, new MockFilterChain());
        filter.doFilter(request("1.2.3.4", "/api/v1/activities"), burned, new MockFilterChain());
        assertThat(burned.getStatus()).isEqualTo(429);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("1.2.3.4", "/actuator/health"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}