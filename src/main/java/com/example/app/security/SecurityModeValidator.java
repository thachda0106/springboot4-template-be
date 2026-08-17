package com.example.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails fast at startup unless exactly one JWT signing/validation mode is configured:
 * HMAC ({@code app.security.jwt.secret-key}) XOR RSA ({@code app.security.jwt.private-key}
 * + {@code public-key}). Rejects both configured, neither configured, or a partial RSA pair.
 */
@Component
public class SecurityModeValidator {

    public SecurityModeValidator(
            @Value("${app.security.jwt.secret-key:}") String secretKey,
            @Value("${app.security.jwt.private-key:}") String privateKey,
            @Value("${app.security.jwt.public-key:}") String publicKey) {
        boolean hmacConfigured = !secretKey.isBlank();
        boolean privateKeyConfigured = !privateKey.isBlank();
        boolean publicKeyConfigured = !publicKey.isBlank();

        if (privateKeyConfigured != publicKeyConfigured) {
            throw new IllegalStateException("RSA JWT mode requires both app.security.jwt.private-key "
                    + "and app.security.jwt.public-key to be configured");
        }
        boolean rsaConfigured = privateKeyConfigured && publicKeyConfigured;

        if (hmacConfigured && rsaConfigured) {
            throw new IllegalStateException("Both HMAC (app.security.jwt.secret-key) and RSA "
                    + "(app.security.jwt.private-key/public-key) JWT modes are configured; choose exactly one");
        }
        if (!hmacConfigured && !rsaConfigured) {
            throw new IllegalStateException("No JWT signing mode configured: set app.security.jwt.secret-key "
                    + "(HMAC) or app.security.jwt.private-key + public-key (RSA)");
        }
    }
}
