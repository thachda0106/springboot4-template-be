package com.example.app.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI documentation for the REST API.
 *
 * <p>springdoc auto-discovers the controllers and produces the spec at
 * {@code /v3/api-docs} (rendered by Swagger UI at {@code /swagger-ui.html}).
 * The resolved paths include the global {@code /api/v1} prefix applied by
 * {@link ApiPathPrefixConfig}.
 *
 * <p>Authentication uses the application's own JWT bearer tokens (see
 * {@code docs/security.md}): the {@code bearerAuth} security scheme lets the
 * Swagger UI "Authorize" button attach a token issued by
 * {@code POST /api/v1/auth/login}. In production the documentation is disabled
 * entirely ({@code springdoc.api-docs.enabled=false}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI apiDocs() {
        return new OpenAPI()
                .info(new Info()
                        .title("Modular Monolith API")
                        .description("""
                                REST API of the modular monolith. Business endpoints require a JWT \
                                access token: call POST /api/v1/auth/login first, then use the \
                                Authorize button with the returned access token (use a current token — \
                                an expired one is rejected even on public endpoints).
                                """)
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}