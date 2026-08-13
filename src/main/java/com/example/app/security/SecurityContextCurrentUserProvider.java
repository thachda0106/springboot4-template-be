package com.example.app.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Spring Security implementation of {@link CurrentUserProvider}.
 * This is the only place in the application that reads the SecurityContext;
 * controllers and use cases go through the {@code CurrentUserProvider} abstraction.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return CurrentUser.of(jwtAuthenticationToken.getToken().getSubject());
        }
        throw new IllegalStateException("No JWT-authenticated user in the security context");
    }
}
