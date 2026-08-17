package com.example.app.user.application.port;

/**
 * Application port for password hashing. Implemented by an adapter in the
 * infrastructure layer wrapping the security module's {@code PasswordEncoder}.
 * Keeps the application layer free of Spring Security types.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
