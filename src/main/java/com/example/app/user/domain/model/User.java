package com.example.app.user.domain.model;

import com.example.app.user.domain.exception.InvalidUserException;

import java.time.Instant;

/**
 * User aggregate root - business user information plus the credentials and role
 * needed for first-party authentication (see docs/security.md).
 *
 * <p>The domain stores only the BCrypt password hash (a String); verification happens
 * in the application layer via a {@code PasswordHasher} port, so the domain stays
 * framework-free. Legacy rows may have a null hash (they cannot log in until one is set).
 */
public class User {

    private final UserId id;
    private final String name;
    private final String email;
    private final UserStatus status;
    private final String passwordHash;
    private final UserRole role;
    private final Instant createdAt;
    private final Instant updatedAt;

    private User(UserId id, String name, String email, UserStatus status, String passwordHash,
                 UserRole role, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.status = status;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Factory used by the application layer when creating a new user. */
    public static User create(String name, String email, String passwordHash, UserRole role) {
        if (name == null || name.isBlank()) {
            throw new InvalidUserException("User name must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new InvalidUserException("User email must not be blank");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new InvalidUserException("User password hash must not be blank");
        }
        if (role == null) {
            throw new InvalidUserException("User role must not be null");
        }
        return new User(UserId.random(), name.trim(), email.trim().toLowerCase(), UserStatus.ACTIVE,
                passwordHash, role, null, null);
    }

    /** Infrastructure-facing factory: reconstructs a user from persisted state. */
    public static User restore(UserId id, String name, String email, UserStatus status, String passwordHash,
                               UserRole role, Instant createdAt, Instant updatedAt) {
        return new User(id, name, email, status, passwordHash, role, createdAt, updatedAt);
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

    public String passwordHash() {
        return passwordHash;
    }

    public UserRole role() {
        return role;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
