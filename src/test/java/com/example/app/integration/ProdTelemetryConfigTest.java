package com.example.app.integration;

import com.example.app.Application;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingProperties;
import org.springframework.boot.test.context.SpringBootTest;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production telemetry contracts:
 *
 * <ul>
 *   <li>the OTLP Authorization header (set via the standard {@code OTEL_EXPORTER_OTLP_HEADERS}
 *       environment variable, which Spring Boot 4.1 maps to
 *       {@code management.opentelemetry.tracing.export.otlp.headers}) reaches the exporter
 *       configuration;</li>
 *   <li>the prod profile is fail-fast: without {@code OTLP_ENDPOINT} (and without the standard
 *       {@code OTEL_EXPORTER_OTLP_ENDPOINT} override) startup refuses to continue - telemetry
 *       is never silently dropped.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "management.opentelemetry.tracing.export.otlp.headers[Authorization]=Bearer test-secret"
})
class ProdTelemetryConfigTest extends AbstractIntegrationTest {

    @Autowired
    private OtlpTracingProperties otlpProperties;

    @Test
    void authorizationHeaderReachesOtlpExporterConfiguration() {
        // Mirrors what the OTEL_EXPORTER_OTLP_HEADERS env var maps to at startup; the
        // application-prod.yml comment documents the operator-facing variable.
        assertThat(otlpProperties.getHeaders())
                .containsEntry("Authorization", "Bearer test-secret");
    }

    @Test
    void prodProfileFailsFastWithoutOtlpEndpoint() {
        // If the developer's environment already carries the standard variables, the
        // mapped property would satisfy the contract and this test's premise is void.
        Assumptions.assumeTrue(
                System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") == null
                        && System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") == null,
                "OTEL_EXPORTER_OTLP_* endpoint env vars must not be set for this test");

        KeyPair keyPair = rsaKeyPair();
        assertThatThrownBy(() -> new SpringApplicationBuilder(Application.class)
                .profiles("prod")
                .web(WebApplicationType.NONE)
                .properties(
                        // Satisfy every other prod placeholder by name (builder
                        // properties sit in the lowest-precedence source, so the yml
                        // ${...} placeholders resolve against these values), so the
                        // ONLY unresolved placeholder is OTLP_ENDPOINT. No DB is
                        // touched: flyway off, no JPA schema validation, Hikari lazy.
                        "DB_URL=jdbc:postgresql://localhost:1/modular_monolith",
                        "DB_USERNAME=postgres",
                        "DB_PASSWORD=postgres",
                        "JWT_PRIVATE_KEY=" + pem(keyPair.getPrivate().getEncoded(), "PRIVATE KEY"),
                        "JWT_PUBLIC_KEY=" + pem(keyPair.getPublic().getEncoded(), "PUBLIC KEY"),
                        "spring.datasource.hikari.initialization-fail-timeout=-1",
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=none")
                .run())
                .hasStackTraceContaining("OTLP_ENDPOINT");
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }

    private static String pem(byte[] der, String type) {
        String base64 = Base64.getEncoder().encodeToString(der).replaceAll("(.{64})", "$1\n");
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
