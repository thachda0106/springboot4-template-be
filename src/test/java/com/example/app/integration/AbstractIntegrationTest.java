package com.example.app.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for all integration tests: boots the full application against a
 * real PostgreSQL container (Testcontainers). Flyway migrations run as part of
 * the Spring context startup, so every test executes against the migrated schema.
 *
 * <p>Testcontainers 2.x dropped the per-database modules, so there is no
 * {@code PostgreSQLContainer} class and {@code @ServiceConnection} has no
 * connection-details factory for a generic postgres container. The classic
 * {@code @DynamicPropertySource} wiring is used instead - still a real
 * PostgreSQL, no H2, no mocks.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withEnv("POSTGRES_DB", "modular_monolith")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", "postgres")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    // Redis for the distributed rate limiter + read cache. Testcontainers 2.x has no
    // per-database module for Redis either - same GenericContainer pattern as PostgreSQL.
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://%s:%d/modular_monolith".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.data.redis.host", () -> REDIS.getHost());
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
