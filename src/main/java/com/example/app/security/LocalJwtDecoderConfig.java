package com.example.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Local and test JWT decoder driven by a shared HMAC secret.
 *
 * <p>Active only when {@code app.security.jwt.secret-key} is configured
 * (local and test profiles). The decoder validates the JWT signature, expiry,
 * issuer and audience — the same trust boundary as the production RSA decoder,
 * so both modes behave consistently.
 *
 * <p>In production the property is absent, so this bean is not created and the
 * RSA decoder ({@link RsaJwtDecoderConfig}) is used instead. The secret-key mode
 * is therefore impossible to enable accidentally in production as long as the
 * property is not set there.
 */
@Configuration(proxyBeanMethods = false)
public class LocalJwtDecoderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.security.jwt.secret-key")
    JwtDecoder localJwtDecoder(
            @Value("${app.security.jwt.secret-key}") String secretKey,
            @Value("${app.security.jwt.issuer:modular-monolith}") String issuer,
            @Value("${app.security.jwt.audience:modular-monolith}") String audience) {
        SecretKey key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)))));
        return decoder;
    }
}
