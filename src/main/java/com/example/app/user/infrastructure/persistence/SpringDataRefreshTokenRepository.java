package com.example.app.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository - internal persistence detail of the user module.
 * Only {@link RefreshTokenRepositoryAdapter} may use it.
 */
interface SpringDataRefreshTokenRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    /**
     * Atomic consume: revokes the token only if it is still valid (not revoked, not
     * expired). The single UPDATE is atomic in PostgreSQL, so concurrent refreshes with
     * the same token serialize here — exactly one caller observes a row count of 1.
     */
    @Modifying
    @Query("""
            UPDATE RefreshTokenJpaEntity t
               SET t.revokedAt = :now
             WHERE t.tokenHash = :tokenHash
               AND t.revokedAt IS NULL
               AND t.expiresAt > :now
            """)
    int consumeIfValid(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}
