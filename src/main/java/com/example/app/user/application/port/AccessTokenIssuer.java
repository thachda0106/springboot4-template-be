package com.example.app.user.application.port;

import java.time.Duration;

/**
 * Application port for issuing access tokens. Implemented by an adapter in the
 * infrastructure layer wrapping the security module's {@code JwtTokenService}.
 * Keeps the application layer free of Spring Security types.
 */
public interface AccessTokenIssuer {

    String issue(String userId, String role);

    Duration accessTokenTtl();
}
