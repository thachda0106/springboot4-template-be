package com.example.app.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues the application's own access tokens.
 *
 * <p>Two mutually exclusive signing modes (enforced by {@link SecurityModeValidator}):
 * <ul>
 *   <li>HMAC HS256 with {@code app.security.jwt.secret-key} (local/test);</li>
 *   <li>RSA RS256 with {@code app.security.jwt.private-key} + {@code public-key} (prod).</li>
 * </ul>
 * In RSA mode a startup self-check signs a throwaway token and validates it with the
 * configured public key, proving the key pair matches.
 *
 * <p>Claims: {@code sub} (user id), {@code role}, {@code iss}, {@code aud}, {@code iat},
 * {@code exp}. The header carries a stable {@code kid} in RSA mode.
 */
@Component
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwsHeader jwsHeader;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;

    public JwtTokenService(
            @Value("${app.security.jwt.secret-key:}") String secretKey,
            @Value("${app.security.jwt.private-key:}") String privateKeyPem,
            @Value("${app.security.jwt.public-key:}") String publicKeyPem,
            @Value("${app.security.jwt.issuer:modular-monolith}") String issuer,
            @Value("${app.security.jwt.audience:modular-monolith}") String audience,
            @Value("${app.security.jwt.access-token-ttl:15m}") Duration accessTokenTtl) {
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtl = accessTokenTtl;

        if (!secretKey.isBlank()) {
            SecretKey key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
            this.jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        } else if (!privateKeyPem.isBlank() && !publicKeyPem.isBlank()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8()
                    .convert(new ByteArrayInputStream(privateKeyPem.getBytes(StandardCharsets.UTF_8)));
            RSAPublicKey publicKey = RsaKeyConverters.x509()
                    .convert(new ByteArrayInputStream(publicKeyPem.getBytes(StandardCharsets.UTF_8)));
            String kid = UUID.nameUUIDFromBytes(publicKey.getModulus().toByteArray()).toString();
            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(kid)
                    .build();
            this.jwtEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
            this.jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build();
            verifyKeyPair(publicKey);
        } else {
            throw new IllegalStateException("No JWT signing key configured: set app.security.jwt.secret-key "
                    + "(HMAC) or app.security.jwt.private-key + public-key (RSA)");
        }
    }

    /** Signs an access token for the given user id and role. */
    public String issueAccessToken(String userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .claim("role", role)
                .issuer(issuer)
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    private void verifyKeyPair(RSAPublicKey publicKey) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("key-pair-self-check")
                .issuer(issuer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        NimbusJwtDecoder.withPublicKey(publicKey).build().decode(token);
    }
}
