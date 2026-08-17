package com.example.app.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Maps the JWT {@code role} claim to a {@code ROLE_<role>} authority using a closed
 * allow-list, while keeping the default {@code scope} → {@code SCOPE_*} mapping.
 *
 * <p>Rules: a missing or unknown role yields no {@code ROLE_*} authority (the caller is
 * authenticated but gets 403 on role-gated endpoints); a present but non-string role claim
 * is treated as a malformed token and fails authentication (401).
 */
@Component
public class RoleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Set<String> KNOWN_ROLES = Set.of("ADMIN", "USER");

    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public RoleJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        delegate.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            Object roleClaim = jwt.getClaim("role");
            if (roleClaim != null && !(roleClaim instanceof String)) {
                throw new BadJwtException("Malformed 'role' claim");
            }
            if (roleClaim instanceof String role && KNOWN_ROLES.contains(role)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return authorities;
        });
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }
}
