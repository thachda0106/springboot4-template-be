package com.example.app.integration;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JDBC query spans: every query executed through the (datasource-micrometer proxied)
 * DataSource becomes a span with OpenTelemetry database conventions - db.system,
 * db.operation and a sanitized db.statement - nested under the request span.
 */
class SqlSpanIntegrationTest extends AbstractApiIntegrationTest {

    @TestConfiguration
    static class SpanCaptureConfig {

        @Bean
        SpanExporter collectingSpanExporter() {
            return new CollectingSpanExporter();
        }
    }

    /**
     * In-memory exporter: Boot 4.1's {@code SpanExporters} aggregates ALL SpanExporter
     * beans, so this one receives every exported span alongside the default OTLP exporter.
     */
    static class CollectingSpanExporter implements SpanExporter {

        static final List<SpanData> SPANS = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            SPANS.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static Map<String, Object> attributes(SpanData span) {
        return span.getAttributes().asMap().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getKey(), Map.Entry::getValue));
    }

    private static String spanNames() {
        return CollectingSpanExporter.SPANS.stream()
                .map(s -> s.getName() + "[" + s.getKind() + "]")
                .collect(Collectors.joining(", "));
    }

    @Test
    void sqlQueriesProduceSanitizedJdbcSpansNestedUnderTheRequest() throws Exception {
        // A 404 read path still runs a SELECT against the activities table.
        String id = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/activities/{id}", id)
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());

        // The batch span processor exports asynchronously - poll briefly for the spans.
        // The JDBC span must be a CHILD of this request's HTTP span (startup queries
        // like Flyway produce their own root spans, which must not be matched).
        long deadline = System.currentTimeMillis() + 15_000;
        SpanData httpSpan = null;
        SpanData jdbcSpan = null;
        while (System.currentTimeMillis() < deadline) {
            SpanData currentHttp = CollectingSpanExporter.SPANS.stream()
                    .filter(s -> s.getKind().name().equals("SERVER"))
                    .findFirst().orElse(null);
            if (currentHttp != null) {
                // The security filter chain inserts INTERNAL spans between the HTTP
                // span and the query, so match by trace id, not direct parentage.
                jdbcSpan = CollectingSpanExporter.SPANS.stream()
                        .filter(s -> "postgresql".equals(attributes(s).get("db.system.name")))
                        .filter(s -> s.getTraceId().equals(currentHttp.getTraceId()))
                        .findFirst().orElse(null);
                httpSpan = currentHttp;
            }
            if (jdbcSpan != null) {
                break;
            }
            Thread.sleep(500);
        }

        if (jdbcSpan == null) {
            fail("no JDBC span nested under the request captured; captured spans: " + spanNames());
        }

        Map<String, Object> dbAttrs = attributes(jdbcSpan);
        // Newer OTel database conventions (v1.39+): db.system.name, db.operation.name,
        // db.query.text (the module's own naming - verified from its bytecode).
        assertThat(dbAttrs.get("db.system.name")).isEqualTo("postgresql");
        assertThat(dbAttrs.get("db.operation.name")).isEqualTo("SELECT");

        // Sanitized query text: placeholders, never bind values or literals from the request.
        String statement = String.valueOf(dbAttrs.get("db.query.text"));
        assertThat(statement).isNotBlank();
        assertThat(statement).doesNotContain(id);
        assertThat(statement).doesNotContain("user-x");

        // Same trace as the request, and nested (not a root span): the query belongs
        // to the request even though security spans sit between them.
        assertThat(jdbcSpan.getTraceId()).isEqualTo(httpSpan.getTraceId());
        assertThat(jdbcSpan.getParentSpanId()).isNotEqualTo("0000000000000000");
    }
}
