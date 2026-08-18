package com.example.app.user.application.usecase;

import com.example.app.user.application.factory.RefreshTokenFactory;
import com.example.app.user.application.policy.RefreshTokenPolicy;
import com.example.app.user.application.port.AccessTokenIssuer;
import com.example.app.user.application.result.AuthResult;
import com.example.app.user.domain.exception.InvalidRefreshTokenException;
import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserStatus;
import com.example.app.user.domain.repository.RefreshTokenRepository;
import com.example.app.user.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Rotates a refresh token: atomically consumes the presented token and, only when the
 * consume wins, issues a successor refresh token and a fresh access token in the same
 * transaction. A consumed/expired/unknown token, or an inactive user, yields 401.
 */
@Service
public class RefreshTokenUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenFactory refreshTokenFactory;
    private final RefreshTokenPolicy refreshTokenPolicy;
    private final Clock clock;

    public RefreshTokenUseCase(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
                               AccessTokenIssuer accessTokenIssuer, RefreshTokenFactory refreshTokenFactory,
                               RefreshTokenPolicy refreshTokenPolicy, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenFactory = refreshTokenFactory;
        this.refreshTokenPolicy = refreshTokenPolicy;
        this.clock = clock;
    }

    @Transactional
    public AuthResult execute(String rawRefreshToken) {
        Instant now = clock.instant();
        String tokenHash = refreshTokenFactory.hash(rawRefreshToken);

        RefreshToken presented = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        // Atomic consume: exactly one concurrent caller wins; the loser gets 401.
        if (!refreshTokenRepository.consumeIfValid(tokenHash, now)) {
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findById(presented.userId())
                .orElseThrow(InvalidRefreshTokenException::new);
        if (user.status() != UserStatus.ACTIVE) {
            throw new InvalidRefreshTokenException();
        }

        String accessToken = accessTokenIssuer.issue(user.id().value().toString(), user.role().name());
        RefreshTokenFactory.IssuedRefreshToken successor =
                refreshTokenFactory.issue(user.id(), refreshTokenPolicy.expiresAt(now));
        refreshTokenRepository.save(successor.token());

        return new AuthResult(accessToken, successor.rawToken(), accessTokenIssuer.accessTokenTtl().toSeconds());
    }
}