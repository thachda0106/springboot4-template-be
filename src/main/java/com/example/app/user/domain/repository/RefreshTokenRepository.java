package com.example.app.user.domain.repository;

import com.example.app.user.domain.model.RefreshToken;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain repository contract for refresh tokens.
 * Implemented in the infrastructure layer; application/domain code never sees JPA.
 */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically consumes a refresh token: revokes it only if it is still valid
     * (not revoked and not expired). Returns true when the caller won the race and
     * may issue a successor; false when the token was already consumed or expired.
     */
    boolean consumeIfValid(String tokenHash, Instant now);
}
