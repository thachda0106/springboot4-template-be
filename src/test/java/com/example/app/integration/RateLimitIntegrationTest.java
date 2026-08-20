package com.example.app.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end quota-layer (rate limit) behavior against real Redis: once the
 * per-IP budget is exhausted, the layer returns the 429 {@code ApiError}
 * contract with {@code Retry-After}. The layers sit before authentication, so
 * unauthenticated requests are limited too.
 */
class RateLimitIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Quota layer: 3 req/min. Throttle limit is high so the quota layer is hit first.
    @DynamicPropertySource
    static void limiterProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.throttle.enabled", () -> "true");
        registry.add("app.security.throttle.limit-for-period", () -> "100");
        registry.add("app.security.throttle.limit-refresh-period", () -> "1s");
        registry.add("app.security.rate-limit.enabled", () -> "true");
        registry.add("app.security.rate-limit.limit-for-period", () -> "3");
        registry.add("app.security.rate-limit.limit-refresh-period", () -> "1m");
    }

    @BeforeEach
    void flushRedis() {
        // Isolate tests: the limiter keys are per-IP and persist across tests.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void rejectsPastTheQuotaWithRateLimitedError() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/activities/00000000-0000-0000-0000-000000000001"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(get("/api/v1/activities/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}