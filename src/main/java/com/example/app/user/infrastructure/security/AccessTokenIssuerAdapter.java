package com.example.app.user.infrastructure.security;

import com.example.app.security.JwtTokenService;
import com.example.app.user.application.port.AccessTokenIssuer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Adapter from the user module's {@link AccessTokenIssuer} port to the security module's
 * {@link JwtTokenService}. The only place the user module touches token issuance.
 */
@Component
public class AccessTokenIssuerAdapter implements AccessTokenIssuer {

    private final JwtTokenService jwtTokenService;

    public AccessTokenIssuerAdapter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    public String issue(String userId, String role) {
        return jwtTokenService.issueAccessToken(userId, role);
    }

    @Override
    public Duration accessTokenTtl() {
        return jwtTokenService.accessTokenTtl();
    }
}
