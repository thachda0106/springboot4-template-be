package com.example.app.security.config;

import com.example.app.security.jwt.RoleJwtAuthenticationConverter;
import com.example.app.security.web.RateLimitFilter;
import com.example.app.security.web.RedisFixedWindowRateLimiter;
import com.example.app.security.web.RequestLoggingFilter;
import com.example.app.security.web.RestAccessDeniedHandler;
import com.example.app.security.web.RestAuthenticationEntryPoint;
import com.example.app.security.web.ThrottleFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Security configuration for the application's own JWT issuance.
 *
 * <p>The application now issues its own access tokens (see {@link JwtTokenService}) and
 * validates them with the HMAC secret (local/test) or RSA public key (prod). Authorization
 * is role-based: the {@code role} claim maps to {@code ROLE_<role>} authorities via
 * {@link RoleJwtAuthenticationConverter}.
 *
 * <p>Cross-cutting request protection, in chain order:
 * <ol>
 *   <li>CORS ({@code CorsFilter}, via {@code http.cors()}) — preflights short-circuit here,
 *       OPTIONS never reach the limiters;</li>
 *   <li>{@link ThrottleFilter} — burst layer, per-IP (Redis fixed-window);</li>
 *   <li>{@link RateLimitFilter} — quota layer, per-IP (Redis fixed-window);</li>
 *   <li>JWT authentication + authorization (SecurityContextHolderFilter onward).</li>
 * </ol>
 * Each limiting layer is constructed only when its {@code enabled} flag is true, so
 * tests/local can disable them entirely (application-test.yml).
 *
 * <p>The fallback for unlisted endpoints is {@code authenticated()} — authentication is
 * always required; role rules are added per endpoint in the reviewed matrix below.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SecurityRateLimitProperties.class)
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final RoleJwtAuthenticationConverter jwtAuthenticationConverter;
    private final SecurityRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          RoleJwtAuthenticationConverter jwtAuthenticationConverter,
                          SecurityRateLimitProperties properties,
                          ObjectMapper objectMapper) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Burst layer: Redis fixed-window counter for the short window. */
    @Bean
    RedisFixedWindowRateLimiter throttleLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        SecurityRateLimitProperties.Throttle throttle = properties.throttle();
        return new RedisFixedWindowRateLimiter("throttle",
                throttle.limitForPeriod(), throttle.limitRefreshPeriod(),
                properties.limiter().redisFailOpen(), redisTemplate, meterRegistry);
    }

    /** Quota layer: Redis fixed-window counter for the sustained window. */
    @Bean
    RedisFixedWindowRateLimiter rateLimitLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        SecurityRateLimitProperties.RateLimit rateLimit = properties.rateLimit();
        return new RedisFixedWindowRateLimiter("rate-limit",
                rateLimit.limitForPeriod(), rateLimit.limitRefreshPeriod(),
                properties.limiter().redisFailOpen(), redisTemplate, meterRegistry);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            RedisFixedWindowRateLimiter throttleLimiter,
                                            RedisFixedWindowRateLimiter rateLimitLimiter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                // Request logging sits just after SecurityContextHolderFilter: the inner
                // chain (JWT auth, authorization, exception translation) has committed the
                // final response (200/401/403/404/...) by the time the line is logged, and
                // the SecurityContext is still populated, so user_id resolves correctly.
                // (Before SecurityContextHolderFilter, SecurityContextHolderFilter's finally
                // clears the context, making user_id always null.) Constructed here, not a
                // bean, to avoid duplicate servlet registration.
                .addFilterAfter(new RequestLoggingFilter(), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Health probes (incl. /liveness and /readiness) and info are public.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Metrics are readable only by a dedicated scraper token carrying
                        // scope=prometheus (least privilege; app-issued tokens have no scope).
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_prometheus")
                        .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/activities/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/activities/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/activities/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/workflow-entries/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        // Per-IP limiting, both layers after the CORS filter (preflights short-circuit
        // before the limiters); registration order is preserved for same-anchor filters:
        // burst (throttle) first, quota (rate limit) second — the shortest window fails
        // fastest. Constructed only when enabled; disabled layers are absent from the chain.
        if (properties.throttle().enabled()) {
            http.addFilterAfter(new ThrottleFilter(throttleLimiter,
                    properties.throttle().limitRefreshPeriod(), objectMapper), CorsFilter.class);
        }
        if (properties.rateLimit().enabled()) {
            http.addFilterAfter(new RateLimitFilter(rateLimitLimiter,
                    properties.rateLimit().limitRefreshPeriod(), objectMapper), CorsFilter.class);
        }

        return http.build();
    }
}