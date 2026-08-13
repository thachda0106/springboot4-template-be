package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the modular monolith.
 *
 * <p>One Spring Boot application, one JVM process, one deployable artifact,
 * one PostgreSQL database. Business logic lives in the {@code activity},
 * {@code workflow} and {@code user} application modules, enforced by
 * Spring Modulith (see the architecture tests in the test sources).
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
