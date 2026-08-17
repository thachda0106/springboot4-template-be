package com.example.app.user.domain.model;

import java.time.Instant;

/**
 * Refresh-token session aggregate.
 *
 * <p>The opaque token value is never stored — only its SHA-256 hash. Rotation revokes
 * the old token and issues a successor; logout revokes. {@code isValid} is evaluated
 * against an explicit instant so the application layer can inject a clock.
 */
public class RefreshToken {

    private final RefreshTokenId id;
    private final UserId userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant revokedAt;
    private final Instant createdAt;

    private RefreshToken(RefreshTokenId id, UserId userId, String tokenHash, Instant expiresAt,
                         Instant revokedAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.createdAt = createdAt;
    }

    /** Factory used by the application layer when issuing a new refresh token. */
    public static RefreshToken issue(UserId userId, String tokenHash, Instant expiresAt) {
        return new RefreshToken(RefreshTokenId.random(), userId, tokenHash, expiresAt, null, Instant.now());
    }

    /** Infrastructure-facing factory: reconstructs a refresh token from persisted state. */
    public static RefreshToken restore(RefreshTokenId id, UserId userId, String tokenHash, Instant expiresAt,
                                       Instant revokedAt, Instant createdAt) {
        return new RefreshToken(id, userId, tokenHash, expiresAt, revokedAt, createdAt);
    }

    /** Revokes this token (idempotent). */
    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    /** True when the token is not revoked and not expired at the given instant. */
    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public RefreshTokenId id() {
        return id;
    }

    public UserId userId() {
        return userId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
