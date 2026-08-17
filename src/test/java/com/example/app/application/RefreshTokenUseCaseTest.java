package com.example.app.application;

import com.example.app.user.application.AuthResult;
import com.example.app.user.application.RefreshTokenFactory;
import com.example.app.user.application.RefreshTokenPolicy;
import com.example.app.user.application.RefreshTokenUseCase;
import com.example.app.user.application.port.AccessTokenIssuer;
import com.example.app.user.domain.exception.InvalidRefreshTokenException;
import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.model.RefreshTokenId;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;
import com.example.app.user.domain.model.UserRole;
import com.example.app.user.domain.model.UserStatus;
import com.example.app.user.domain.repository.RefreshTokenRepository;
import com.example.app.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application-layer tests for RefreshTokenUseCase with mocked collaborators - no Spring context.
 */
class RefreshTokenUseCaseTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final RefreshTokenFactory refreshTokenFactory = new RefreshTokenFactory();
    private final RefreshTokenPolicy refreshTokenPolicy = new RefreshTokenPolicy(Duration.ofDays(7));
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final RefreshTokenUseCase useCase = new RefreshTokenUseCase(
            refreshTokenRepository, userRepository, accessTokenIssuer, refreshTokenFactory,
            refreshTokenPolicy, clock);

    private final UserId userId = UserId.random();
    private final String rawToken = "raw-refresh-token";
    private final String tokenHash = refreshTokenFactory.hash(rawToken);

    private RefreshToken validToken() {
        return RefreshToken.restore(RefreshTokenId.random(), userId, tokenHash,
                clock.instant().plusSeconds(3600), null, clock.instant());
    }

    @Test
    void refreshRotatesTokenAndIssuesNewAccessToken() {
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(validToken()));
        when(refreshTokenRepository.consumeIfValid(tokenHash, clock.instant())).thenReturn(true);
        User user = User.restore(userId, "Alice", "a@b.com", UserStatus.ACTIVE, "h", UserRole.USER, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accessTokenIssuer.issue(userId.value().toString(), "USER")).thenReturn("new-access");
        when(accessTokenIssuer.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        AuthResult result = useCase.execute(rawToken);

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).save(any()); // successor persisted
    }

    @Test
    void refreshWithUnknownTokenThrows() {
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(rawToken))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refreshWithConsumedTokenThrows() {
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(validToken()));
        when(refreshTokenRepository.consumeIfValid(tokenHash, clock.instant())).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(rawToken))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refreshWithInactiveUserThrows() {
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(validToken()));
        when(refreshTokenRepository.consumeIfValid(tokenHash, clock.instant())).thenReturn(true);
        User user = User.restore(userId, "Alice", "a@b.com", UserStatus.INACTIVE, "h", UserRole.USER, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> useCase.execute(rawToken))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).save(any());
    }
}
