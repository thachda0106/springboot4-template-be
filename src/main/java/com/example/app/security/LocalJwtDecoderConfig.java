package com.example.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Local and test JWT decoder driven by a shared HMAC secret.
 *
 * <p>Active only when {@code app.security.jwt.secret-key} is configured
 * (local and test profiles). The decoder validates the JWT signature but not an
 * issuer, which is the documented trade-off of the development mode.
 *
 * <p>In production the property is absent, so this bean is not created and
 * Boot's auto-configuration builds the decoder from
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (OIDC discovery
 * against the external Identity Provider). The secret-key mode is therefore
 * impossible to enable accidentally in production as long as the property is
 * not set there.
 */
@Configuration(proxyBeanMethods = false)
public class LocalJwtDecoderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.security.jwt.secret-key")
    JwtDecoder localJwtDecoder(@Value("${app.security.jwt.secret-key}") String secretKey) {
        SecretKey key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
