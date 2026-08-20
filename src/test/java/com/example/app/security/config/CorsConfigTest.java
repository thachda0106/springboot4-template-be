package com.example.app.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the CORS configuration source: origins, methods, headers and
 * the deliberate no-credentials setting.
 */
class CorsConfigTest {

    private CorsConfiguration configuration() {
        SecurityRateLimitProperties properties = new SecurityRateLimitProperties(
                new SecurityRateLimitProperties.Cors(List.of("http://localhost:3000", "https://app.example.com")),
                new SecurityRateLimitProperties.Throttle(true, 20, Duration.ofSeconds(1)),
                new SecurityRateLimitProperties.RateLimit(true, 100, Duration.ofMinutes(1)),
                new SecurityRateLimitProperties.Limiter(true));

        CorsConfigurationSource source = new CorsConfig().corsConfigurationSource(properties);
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/activities"));
    }

    @Test
    void exposesConfiguredOrigins() {
        assertThat(configuration().getAllowedOrigins())
                .containsExactly("http://localhost:3000", "https://app.example.com");
    }

    @Test
    void allowsTheStandardApiMethods() {
        assertThat(configuration().getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    @Test
    void allowsAnyHeaderWithoutCredentials() {
        CorsConfiguration configuration = configuration();
        assertThat(configuration.getAllowedHeaders()).containsExactly("*");
        // JWT bearer auth - cookies/credentials are never used, so credentials
        // must stay disabled (allowing them would forbid wildcard headers).
        assertThat(configuration.getAllowCredentials()).isFalse();
    }

    @Test
    void cachesPreflightResults() {
        assertThat(configuration().getMaxAge()).isEqualTo(3600L);
    }
}