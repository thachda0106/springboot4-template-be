package com.example.app.integration;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.app.security.web.RequestLoggingFilter;
import com.example.app.shared.AfterCommitMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Observability: Prometheus metrics (business counters, scraper authorization),
 * /actuator/info build metadata, health probes, and the request log line.
 *
 * <p>The request-log assertions use a {@link ListAppender} attached to the
 * {@code RequestLoggingFilter} logger: {@code OutputCaptureExtension} is unreliable
 * here because the logback context is a JVM singleton initialized by another test
 * class with the original {@code System.out}.
 */
class ObservabilityIntegrationTest extends AbstractApiIntegrationTest {

    private static final ListAppender<ILoggingEvent> REQUEST_LOG_APPENDER = new ListAppender<>();

    /**
     * Captures Hibernate SQL statements ({@code org.hibernate.SQL}) and bind parameter
     * values ({@code org.hibernate.orm.jdbc.bind}) for the DEBUG-logging test.
     */
    private static final ListAppender<ILoggingEvent> SQL_LOG_APPENDER = new ListAppender<>();

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void attachLogAppenders() {
        // Attach only after Spring has initialized the logback context: for a
        // @SpringBootTest the context (and Boot's logging initialization, which calls
        // LoggerContext.reset()) is created between @BeforeAll and the test body, so
        // an appender attached earlier would be detached before any request happens.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        if (!logger.isAttached(REQUEST_LOG_APPENDER)) {
            logger.addAppender(REQUEST_LOG_APPENDER);
            REQUEST_LOG_APPENDER.start();
        }
        REQUEST_LOG_APPENDER.list.clear();

        for (String category : new String[] {"org.hibernate.SQL", "org.hibernate.orm.jdbc.bind"}) {
            ch.qos.logback.classic.Logger sqlLogger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(category);
            if (!sqlLogger.isAttached(SQL_LOG_APPENDER)) {
                sqlLogger.addAppender(SQL_LOG_APPENDER);
                SQL_LOG_APPENDER.start();
            }
        }
        SQL_LOG_APPENDER.list.clear();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        // HashMap collector: tolerates null values (e.g. user_id on anonymous requests).
        return event.getKeyValuePairs().stream()
                .collect(HashMap::new, (map, kvp) -> map.put(kvp.key, kvp.value), HashMap::putAll);
    }

    private static List<ILoggingEvent> requestLogEvents() {
        return REQUEST_LOG_APPENDER.list;
    }

    private String createUserWithPassword(String name, String email, String password, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "email": "%s", "password": "%s", "role": "%s"}
                                """.formatted(name, email, password, role)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createActivity(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro %s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String scrapePrometheus() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().jwt(j -> j.subject("scraper").claim("scope", "prometheus"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_prometheus"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void prometheusRequiresScraperScope() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().jwt(j -> j.subject("scraper").claim("scope", "prometheus"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_prometheus"))))
                .andExpect(status().isOk());
    }

    @Test
    void scraperTokenIsLeastPrivilege() throws Exception {
        // A scope-only token (no role claim) may scrape metrics but must not reach any
        // role-gated business endpoint: RoleJwtAuthenticationConverter grants ROLE_* only
        // from a known role claim, so scope=prometheus alone yields just SCOPE_prometheus.
        var scraped = jwt().jwt(j -> j.subject("scraper-1").claim("scope", "prometheus"))
                .authorities(new SimpleGrantedAuthority("SCOPE_prometheus"));

        mockMvc.perform(get("/actuator/prometheus").with(scraped))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users").with(scraped)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"x@example.com","password":"s3cret-pass","role":"USER"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/activities").with(scraped)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Retro"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/activities/{id}", UUID.randomUUID()).with(scraped))
                .andExpect(status().isForbidden());
    }

    @Test
    void activityLifecycleMetricReflectsApiCall() throws Exception {
        String userId = createUserWithPassword("Alice", "alice." + UUID.randomUUID() + "@example.com",
                "s3cret-pass", "USER");
        createActivity(userId);

        assertThat(scrapePrometheus()).contains("app_activities_lifecycle_total{action=\"created\"");
    }

    @Test
    void loginSuccessAndFailureAreCounted() throws Exception {
        String email = "bob." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Bob", email, "s3cret-pass", "USER");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "s3cret-pass"}
                                """.formatted(email)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong-pass"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized());

        String body = scrapePrometheus();
        assertThat(body).contains("app_auth_logins_total{outcome=\"success\"");
        assertThat(body).contains("app_auth_logins_total{outcome=\"failure\"");
    }

    @Test
    void hikaricpConnectionPoolMetricsExposedInScrape() throws Exception {
        // HikariCP metrics are auto-registered by Boot (DataSourcePoolMetricsAutoConfiguration).
        // Presence-based: gauges exist at 0 once the pool starts; never assert counts.
        String body = scrapePrometheus();
        assertThat(body).contains("hikaricp_connections{pool=");
        assertThat(body).contains("hikaricp_connections_active{pool=");
    }

    @Test
    void dbQueriesAreLoggedAtDebugLevel() throws Exception {
        // The test profile intentionally does not log SQL (INFO default); set the levels
        // programmatically to prove the behavior the local profile wires in, then restore.
        ch.qos.logback.classic.Logger sqlLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        ch.qos.logback.classic.Logger bindLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind");
        ch.qos.logback.classic.Level originalSql = sqlLogger.getLevel();
        ch.qos.logback.classic.Level originalBind = bindLogger.getLevel();
        try {
            sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            bindLogger.setLevel(ch.qos.logback.classic.Level.TRACE);

            // 404 read path runs a SELECT against the activities table.
            mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID())
                            .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isNotFound());

            assertThat(SQL_LOG_APPENDER.list).anyMatch(event ->
                    event.getFormattedMessage().toLowerCase().startsWith("select"));
            assertThat(SQL_LOG_APPENDER.list).anyMatch(event ->
                    "org.hibernate.orm.jdbc.bind".equals(event.getLoggerName()));
        } finally {
            sqlLogger.setLevel(originalSql);   // null restores inherited level
            bindLogger.setLevel(originalBind);
        }
    }

    @Test
    void workflowMetricIncrementedOnActivityCreation() throws Exception {
        String userId = createUserWithPassword("Carol", "carol." + UUID.randomUUID() + "@example.com",
                "s3cret-pass", "USER");
        createActivity(userId);

        assertThat(scrapePrometheus()).contains("app_workflow_entries_total");
    }

    @Test
    void rollbackDoesNotIncrementCounter() throws Exception {
        // Other tests may already have incremented the deleted counter, so compare the
        // shared registry before/after instead of asserting an absolute scrape.
        Counter deleted = meterRegistry.find("app.activities.lifecycle").tag("action", "deleted").counter();
        double before = deleted == null ? 0 : deleted.count();

        // DELETE of a non-existent activity throws inside the transaction -> rollback.
        mockMvc.perform(delete("/api/v1/activities/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("admin").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());

        Counter after = meterRegistry.find("app.activities.lifecycle").tag("action", "deleted").counter();
        assertThat(after == null ? 0 : after.count()).isEqualTo(before);
    }

    @Test
    void afterCommitSynchronizationIncrementsOnCommit() {
        // Real transaction against the Testcontainers DB: the synchronization registered
        // by AfterCommitMetrics must fire after commit (the branch the HTTP-level tests
        // cannot isolate - the API tests only observe the net effect).
        Counter deleted = meterRegistry.counter("app.activities.lifecycle", "action", "deleted");
        double before = deleted.count();
        TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
        AfterCommitMetrics.incrementAfterCommit(deleted);
        transactionManager.commit(tx);
        assertThat(deleted.count()).isEqualTo(before + 1);
    }

    @Test
    void afterCommitSynchronizationSkipsIncrementOnRollback() {
        // The increment is registered, then the transaction is rolled back: the counter
        // must not move even though the increment was requested inside the transaction.
        Counter deleted = meterRegistry.counter("app.activities.lifecycle", "action", "deleted");
        double before = deleted.count();
        TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
        AfterCommitMetrics.incrementAfterCommit(deleted);
        transactionManager.rollback(tx);
        assertThat(deleted.count()).isEqualTo(before);
    }

    @Test
    void infoEndpointContainsGitBuildAndJava() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.commit.id").isNotEmpty())
                .andExpect(jsonPath("$.build.time").isNotEmpty())
                .andExpect(jsonPath("$.java.version").isNotEmpty())
                // The env contributor is intentionally disabled (public endpoint).
                .andExpect(jsonPath("$.env").doesNotExist());
    }

    @Test
    void healthEndpointReportsUpWithProbes() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // The readiness group explicitly includes the db indicator: an instance
                // whose database is down must not receive traffic (see ReadinessIntegrationTest).
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void requestLogLineContainsRequestMetadata() throws Exception {
        String userId = createUserWithPassword("Dave", "dave." + UUID.randomUUID() + "@example.com",
                "s3cret-pass", "USER");

        mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());

        ILoggingEvent event = requestLogEvents().stream()
                .filter(e -> String.valueOf(keyValues(e).get("url.path")).startsWith("/api/v1/activities/"))
                .findFirst().orElseThrow();
        Map<String, Object> kv = keyValues(event);
        assertThat(kv.get("http.request.method")).isEqualTo("GET");
        assertThat((String) kv.get("url.path")).startsWith("/api/v1/activities/");
        assertThat(kv.get("http.response.status_code")).isEqualTo(404);
        assertThat(kv.get("duration_ms")).isInstanceOf(Number.class);
        assertThat(kv.get("user.id")).isEqualTo(userId);
        assertThat(kv.get("event.outcome")).isEqualTo("failure");
        assertThat(event.getMDCPropertyMap().get("traceId")).isNotBlank();
    }

    @Test
    void requestLogCoversRejectedRequests() throws Exception {
        // Unauthenticated -> 401, no user.id.
        mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        assertThat(requestLogEvents()).anySatisfy(event -> {
            assertThat(keyValues(event).get("http.response.status_code")).isEqualTo(401);
            assertThat(keyValues(event).get("user.id")).isNull();
            assertThat(keyValues(event).get("event.outcome")).isEqualTo("failure");
        });

        // Authenticated but forbidden -> 403.
        mockMvc.perform(delete("/api/v1/activities/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        assertThat(requestLogEvents()).anySatisfy(event -> {
            assertThat(keyValues(event).get("http.response.status_code")).isEqualTo(403);
            assertThat(keyValues(event).get("event.outcome")).isEqualTo("failure");
        });

        // Invalid bearer token -> 401.
        mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized());
        assertThat(requestLogEvents().stream()
                .filter(event -> Integer.valueOf(401).equals(keyValues(event).get("http.response.status_code")))
                .count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void requestLogExcludesQueryStringAndHealthProbes() throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/activities/{id}", id)
                        .queryParam("secret", "value")
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());

        ILoggingEvent event = requestLogEvents().stream()
                .filter(e -> String.valueOf(keyValues(e).get("url.path")).startsWith("/api/v1/activities/"))
                .findFirst().orElseThrow();
        assertThat((String) keyValues(event).get("url.path")).isEqualTo("/api/v1/activities/" + id);
        assertThat((String) keyValues(event).get("url.path")).doesNotContain("secret=value");

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        assertThat(requestLogEvents()).noneMatch(e ->
                String.valueOf(keyValues(e).get("url.path")).startsWith("/actuator/health"));
    }
}