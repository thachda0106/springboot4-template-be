package com.example.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 Resource Server security configuration.
 *
 * <p>The application never issues tokens: it validates JWTs issued by an external
 * Identity Provider (Keycloak, Auth0, Cognito, Okta, ...) configured via
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} in production, or a
 * shared HMAC secret in local/test mode (see {@link LocalJwtDecoderConfig}).
 *
 * <p>Authorization is scope-based: the default JWT converter maps the standard
 * {@code scope}/{@code scp} claims to authorities prefixed with {@code SCOPE_}
 * (e.g. scope {@code activity:write} becomes authority {@code SCOPE_activity:write}).
 *
 * <p>No security type ever reaches the domain layer: controllers translate the
 * authenticated principal into {@link CurrentUser} via {@link CurrentUserProvider}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/activities/**").hasAuthority("SCOPE_activity:write")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/activities/**").hasAuthority("SCOPE_activity:write")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/activities/**").hasAuthority("SCOPE_activity:admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/workflow-entries/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").hasAuthority("SCOPE_user:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
