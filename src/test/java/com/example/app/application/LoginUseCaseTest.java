package com.example.app.application;

import com.example.app.user.application.AuthResult;
import com.example.app.user.application.LoginUseCase;
import com.example.app.user.application.RefreshTokenFactory;
import com.example.app.user.application.RefreshTokenPolicy;
import com.example.app.user.application.port.AccessTokenIssuer;
import com.example.app.user.application.port.PasswordHasher;
import com.example.app.user.domain.exception.InvalidCredentialsException;
import com.example.app.user.domain.exception.InvalidUserException;
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
 * Application-layer tests for LoginUseCase with mocked collaborators - no Spring context.
 */
class LoginUseCaseTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final RefreshTokenFactory refreshTokenFactory = new RefreshTokenFactory();
    private final RefreshTokenPolicy refreshTokenPolicy = new RefreshTokenPolicy(Duration.ofDays(7));
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final LoginUseCase useCase = new LoginUseCase(
            userRepository, refreshTokenRepository, passwordHasher, accessTokenIssuer,
            refreshTokenFactory, refreshTokenPolicy, clock);

    private User activeUser(String email) {
        return User.restore(UserId.random(), "Alice", email, UserStatus.ACTIVE,
                "stored-hash", UserRole.USER, null, null);
    }

    @Test
    void loginSuccessIssuesTokensAndSavesRefreshToken() {
        User user = activeUser("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("secret", "stored-hash")).thenReturn(true);
        when(accessTokenIssuer.issue(user.id().value().toString(), "USER")).thenReturn("access-token");
        when(accessTokenIssuer.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        AuthResult result = useCase.execute("  Alice@Example.com ", "secret");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.expiresInSeconds()).isEqualTo(900);
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void loginWithWrongPasswordThrows() {
        User user = activeUser("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute("alice@example.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void loginWithUnknownEmailThrows() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("ghost@example.com", "whatever"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwordHasher).matches(any(), any()); // dummy-hash compare still runs
    }

    @Test
    void loginWithInactiveUserThrows() {
        User user = User.restore(UserId.random(), "Alice", "alice@example.com", UserStatus.INACTIVE,
                "stored-hash", UserRole.USER, null, null);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> useCase.execute("alice@example.com", "secret"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithNullLegacyHashThrows() {
        User user = User.restore(UserId.random(), "Alice", "alice@example.com", UserStatus.ACTIVE,
                null, UserRole.USER, null, null);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> useCase.execute("alice@example.com", "secret"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithOversizedPasswordThrows() {
        assertThatThrownBy(() -> useCase.execute("alice@example.com", "x".repeat(100)))
                .isInstanceOf(InvalidUserException.class);
        verify(userRepository, never()).findByEmail(any());
    }
}
