package com.example.app.user.application;

import com.example.app.user.application.port.AccessTokenIssuer;
import com.example.app.user.application.port.PasswordHasher;
import com.example.app.user.domain.exception.InvalidCredentialsException;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserStatus;
import com.example.app.user.domain.repository.RefreshTokenRepository;
import com.example.app.user.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Authenticates a user with email + password and issues an access token plus a refresh
 * token. All failure cases (unknown email, wrong password, inactive account, legacy
 * account without a password) produce the same {@link InvalidCredentialsException} and
 * run a dummy BCrypt comparison, so callers cannot enumerate accounts or measure
 * account state through timing.
 */
@Service
public class LoginUseCase {

    /** Valid BCrypt hash of a random value, used to equalize timing for unknown accounts. */
    private static final String DUMMY_HASH = "$2a$10$FWBxOjtaDz0s2DeH7wGkO.LM2KvD0pAQPI92.k8.9E4d5Mb1Ipav6";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenFactory refreshTokenFactory;
    private final RefreshTokenPolicy refreshTokenPolicy;
    private final Clock clock;

    public LoginUseCase(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                        PasswordHasher passwordHasher, AccessTokenIssuer accessTokenIssuer,
                        RefreshTokenFactory refreshTokenFactory, RefreshTokenPolicy refreshTokenPolicy,
                        Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenFactory = refreshTokenFactory;
        this.refreshTokenPolicy = refreshTokenPolicy;
        this.clock = clock;
    }

    @Transactional
    public AuthResult execute(String email, String rawPassword) {
        PasswordRules.requireWithinBcryptLimit(rawPassword);
        String normalizedEmail = email.trim().toLowerCase();
        Instant now = clock.instant();

        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null || user.status() != UserStatus.ACTIVE || user.passwordHash() == null) {
            passwordHasher.matches(rawPassword, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }
        if (!passwordHasher.matches(rawPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        String accessToken = accessTokenIssuer.issue(user.id().value().toString(), user.role().name());
        RefreshTokenFactory.IssuedRefreshToken issued =
                refreshTokenFactory.issue(user.id(), refreshTokenPolicy.expiresAt(now));
        refreshTokenRepository.save(issued.token());

        return new AuthResult(accessToken, issued.rawToken(), accessTokenIssuer.accessTokenTtl().toSeconds());
    }
}