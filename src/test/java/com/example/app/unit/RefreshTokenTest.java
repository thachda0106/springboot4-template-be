package com.example.app.unit;

import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.model.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure domain unit tests for the refresh-token aggregate - no Spring context.
 */
class RefreshTokenTest {

    private final UserId userId = UserId.random();

    @Test
    void issuedTokenIsValidUntilExpiry() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        RefreshToken token = RefreshToken.issue(userId, "hash", now.plusSeconds(3600));

        assertThat(token.isValid(now)).isTrue();
        assertThat(token.isValid(now.plusSeconds(3599))).isTrue();
        assertThat(token.isValid(now.plusSeconds(3600))).isFalse(); // at expiry -> invalid
        assertThat(token.revokedAt()).isNull();
    }

    @Test
    void revokeMakesTokenInvalidAndIsIdempotent() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        RefreshToken token = RefreshToken.issue(userId, "hash", now.plusSeconds(3600));

        token.revoke();
        assertThat(token.isValid(now)).isFalse();
        assertThat(token.revokedAt()).isNotNull();

        Instant firstRevokedAt = token.revokedAt();
        token.revoke();
        assertThat(token.revokedAt()).isEqualTo(firstRevokedAt);
    }
}
