package com.example.app.security;

/**
 * Application-level abstraction of the currently authenticated user.
 *
 * <p>Deliberately free of Spring Security types: application use cases and
 * domain logic depend on this record, never on {@code Authentication},
 * {@code Jwt} or {@code SecurityContext}. The mapping from JWT to
 * {@code CurrentUser} happens only at the API boundary.
 */
public record CurrentUser(String id) {

    public static CurrentUser of(String id) {
        return new CurrentUser(id);
    }
}
