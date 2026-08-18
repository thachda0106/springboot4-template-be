package com.example.app.security.config;

import com.example.app.security.jwt.RoleJwtAuthenticationConverter;
import com.example.app.security.web.RequestLoggingFilter;
import com.example.app.security.web.RestAccessDeniedHandler;
import com.example.app.security.web.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Security configuration for the application's own JWT issuance.
 *
 * <p>The application now issues its own access tokens (see {@link JwtTokenService}) and
 * validates them with the HMAC secret (local/test) or RSA public key (prod). Authorization
 * is role-based: the {@code role} claim maps to {@code ROLE_<role>} authorities via
 * {@link RoleJwtAuthenticationConverter}.
 *
 * <p>The fallback for unlisted endpoints is {@code authenticated()} — authentication is
 * always required; role rules are added per endpoint in the reviewed matrix below.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final RoleJwtAuthenticationConverter jwtAuthenticationConverter;

    public SecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          RoleJwtAuthenticationConverter jwtAuthenticationConverter) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
        return http.build();
    }
}
