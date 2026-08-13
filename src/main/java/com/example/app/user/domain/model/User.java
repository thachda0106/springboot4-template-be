package com.example.app.user.domain.model;

import com.example.app.user.domain.exception.InvalidUserException;

import java.time.Instant;

/**
 * User aggregate root - business user information only.
 *
 * <p>Deliberately contains NO credentials, passwords or authentication state:
 * authentication is externalized to an OAuth2/OIDC Identity Provider and the JWT
 * {@code sub} claim identifies the user (see docs/security.md).
 */
public class User {

    private final UserId id;
    private final String name;
    private final String email;
    private final UserStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private User(UserId id, String name, String email, UserStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Factory used by the application layer when creating a new user. */
    public static User create(String name, String email) {
        if (name == null || name.isBlank()) {
            throw new InvalidUserException("User name must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new InvalidUserException("User email must not be blank");
        }
        return new User(UserId.random(), name.trim(), email.trim().toLowerCase(), UserStatus.ACTIVE, null, null);
    }

    /** Infrastructure-facing factory: reconstructs a user from persisted state. */
    public static User restore(UserId id, String name, String email, UserStatus status,
                               Instant createdAt, Instant updatedAt) {
        return new User(id, name, email, status, createdAt, updatedAt);
    }

    public UserId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public UserStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
