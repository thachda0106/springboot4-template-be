package com.example.app.security.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Production JWT decoder driven by the application's own RSA public key.
 *
 * <p>Active only when {@code app.security.jwt.public-key} is configured (prod profile).
 * Validates signature, expiry, issuer and audience — the trust boundary the previous
 * OIDC {@code issuer-uri} mode provided. The HMAC decoder (local/test) is mutually
 * exclusive with this one (see {@link SecurityModeValidator}).
 */
@Configuration(proxyBeanMethods = false)
public class RsaJwtDecoderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.security.jwt.public-key")
    JwtDecoder rsaJwtDecoder(
            @Value("${app.security.jwt.public-key}") String publicKeyPem,
            @Value("${app.security.jwt.issuer:modular-monolith}") String issuer,
            @Value("${app.security.jwt.audience:modular-monolith}") String audience) {
        RSAPublicKey publicKey = RsaKeyConverters.x509()
                .convert(new ByteArrayInputStream(publicKeyPem.getBytes(StandardCharsets.UTF_8)));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)))));
        return decoder;
    }
}
