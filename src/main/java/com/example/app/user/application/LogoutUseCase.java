package com.example.app.user.application;

import com.example.app.user.domain.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Revokes a refresh token on logout. Idempotent: an unknown or already-revoked token is
 * a no-op (204). The caller passes the authenticated user id so a token belonging to a
 * different user is never revoked.
 */
@Service
public class LogoutUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenFactory refreshTokenFactory;
    private final Clock clock;

    public LogoutUseCase(RefreshTokenRepository refreshTokenRepository, RefreshTokenFactory refreshTokenFactory,
                         Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenFactory = refreshTokenFactory;
        this.clock = clock;
    }

    @Transactional
    public void execute(String rawRefreshToken, String currentUserId) {
        Instant now = clock.instant();
        String tokenHash = refreshTokenFactory.hash(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(token -> token.userId().value().toString().equals(currentUserId))
                .ifPresent(token -> refreshTokenRepository.consumeIfValid(tokenHash, now));
    }
}