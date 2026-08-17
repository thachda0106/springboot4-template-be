package com.example.app.user.application;

import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.model.UserId;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates opaque refresh tokens and their SHA-256 hashes. The raw token is returned to
 * the client exactly once; only the hash is persisted.
 */
@Component
public class RefreshTokenFactory {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A freshly issued raw token plus the domain aggregate holding its hash. */
    public record IssuedRefreshToken(String rawToken, RefreshToken token) {
    }

    public IssuedRefreshToken issue(UserId userId, Instant expiresAt) {
        String raw = generateToken();
        return new IssuedRefreshToken(raw, RefreshToken.issue(userId, hash(raw), expiresAt));
    }

    /** SHA-256 hex digest of a token, used as the persisted lookup key. */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
