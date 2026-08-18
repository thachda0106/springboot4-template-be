package com.example.app.user.application.policy;

import com.example.app.user.domain.exception.InvalidUserException;

import java.nio.charset.StandardCharsets;

/**
 * BCrypt password policy: at most 72 UTF-8 bytes (BCrypt's hard input limit). Enforced
 * on both create and login so oversized or multibyte passwords cannot be truncated into
 * collisions.
 */
public final class PasswordRules {

    private PasswordRules() {
    }

    public static void requireWithinBcryptLimit(String rawPassword) {
        if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidUserException("Password must not exceed 72 bytes");
        }
    }
}