package com.example.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Readiness contract: the readiness probe must track the database. An instance whose
 * database is unreachable must not receive traffic (503 on the probe), even though the
 * process is up and the liveness probe still reports UP.
 *
 * <p>This class owns a <b>private</b> Postgres container instead of inheriting
 * {@link AbstractIntegrationTest}'s: the whole test suite shares one JVM and therefore
 * one static container, so stopping the shared database would break every subsequent
 * test class. A private container and its own Spring context keep the outage contained.
 */
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=5000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessIntegrationTest {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withEnv("POSTGRES_DB", "modular_monolith")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", "postgres")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://%s:%d/modular_monolith".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void readinessFollowsDatabaseAvailability() throws Exception {
        // Database up: readiness is UP and explicitly reports the db component.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));

        POSTGRES.stop();

        // The health indicator blocks up to the (5s) HikariCP connection timeout before
        // reporting DOWN, so poll for the transition instead of assuming it is instant.
        long deadline = System.currentTimeMillis() + 60_000;
        MvcResult result = null;
        while (System.currentTimeMillis() < deadline) {
            result = mockMvc.perform(get("/actuator/health/readiness")).andReturn();
            if (result.getResponse().getStatus() == 503) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(result).isNotNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        // DOWN is the most severe status in Spring's order, so the readiness group
        // aggregates db=DOWN + readinessState=UP to DOWN (not OUT_OF_SERVICE).
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("components").path("db").path("status").asText()).isEqualTo("DOWN");
        // The process itself is still alive: liveness must stay UP.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
