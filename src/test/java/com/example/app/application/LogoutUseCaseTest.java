package com.example.app.application;

import com.example.app.user.application.factory.RefreshTokenFactory;
import com.example.app.user.application.usecase.LogoutUseCase;
import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.model.RefreshTokenId;
import com.example.app.user.domain.model.UserId;
import com.example.app.user.domain.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application-layer tests for LogoutUseCase with mocked collaborators - no Spring context.
 */
class LogoutUseCaseTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RefreshTokenFactory refreshTokenFactory = new RefreshTokenFactory();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final LogoutUseCase useCase = new LogoutUseCase(refreshTokenRepository, refreshTokenFactory, clock);

    private final UserId userId = UserId.random();
    private final String rawToken = "raw-refresh-token";
    private final String tokenHash = refreshTokenFactory.hash(rawToken);

    @Test
    void logoutRevokesOwnToken() {
        RefreshToken token = RefreshToken.restore(RefreshTokenId.random(), userId, tokenHash,
                clock.instant().plusSeconds(3600), null, clock.instant());
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        useCase.execute(rawToken, userId.value().toString());

        verify(refreshTokenRepository).consumeIfValid(tokenHash, clock.instant());
    }

    @Test
    void logoutDoesNotRevokeAnotherUsersToken() {
        RefreshToken token = RefreshToken.restore(RefreshTokenId.random(), userId, tokenHash,
                clock.instant().plusSeconds(3600), null, clock.instant());
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        useCase.execute(rawToken, UserId.random().value().toString());

        verify(refreshTokenRepository, never()).consumeIfValid(any(), any());
    }

    @Test
    void logoutWithUnknownTokenIsNoOp() {
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        useCase.execute(rawToken, userId.value().toString());

        verify(refreshTokenRepository, never()).consumeIfValid(any(), any());
    }
}
