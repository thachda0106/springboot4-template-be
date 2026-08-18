package com.example.app.unit;

import com.example.app.security.jwt.RoleJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the role-claim → ROLE_* authority converter.
 */
class RoleJwtAuthenticationConverterTest {

    private final RoleJwtAuthenticationConverter converter = new RoleJwtAuthenticationConverter();

    private Jwt jwt(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }

    private List<String> authorities(String role) {
        AbstractAuthenticationToken token = converter.convert(jwt(role));
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void mapsAdminRoleToRoleAdmin() {
        assertThat(authorities("ADMIN")).contains("ROLE_ADMIN");
    }

    @Test
    void mapsUserRoleToRoleUser() {
        assertThat(authorities("USER")).contains("ROLE_USER");
    }

    @Test
    void missingRoleYieldsNoRoleAuthority() {
        assertThat(authorities(null)).doesNotContain("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void unknownRoleYieldsNoRoleAuthority() {
        assertThat(authorities("SUPERUSER")).doesNotContain("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void malformedRoleClaimFailsAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("role", List.of("ADMIN", "USER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(BadJwtException.class);
    }
}
