package com.example.app.user.application.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Owns the refresh-token lifetime policy: reads {@code app.security.jwt.refresh-token-ttl},
 * validates it at startup, and computes expiry instants. The same policy is used on login
 * and rotation, so both paths agree on the absolute TTL.
 */
@Component
public class RefreshTokenPolicy {

    private final Duration refreshTokenTtl;

    public RefreshTokenPolicy(@Value("${app.security.jwt.refresh-token-ttl:7d}") Duration refreshTokenTtl) {
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
            throw new IllegalStateException("app.security.jwt.refresh-token-ttl must be a positive duration");
        }
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Instant expiresAt(Instant now) {
        return now.plus(refreshTokenTtl);
    }
}
