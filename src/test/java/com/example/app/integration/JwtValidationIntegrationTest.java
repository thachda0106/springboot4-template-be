package com.example.app.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real JWT validation against the actual decoder configured for the test
 * profile (HMAC mode): valid signature -> 200/201, wrong signature -> 401,
 * expired token -> 401, garbage token -> 401.
 */
class JwtValidationIntegrationTest extends AbstractApiIntegrationTest {

    @Value("${app.security.jwt.secret-key}")
    private String jwtSecret;

    @Test
    void validJwtIsAccepted() throws Exception {
        String userId = createUser("jwt-alice");

        String token = mint(userId, "activity:read activity:write", jwtSecret, Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/activities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(userId));
    }

    @Test
    void tokenSignedWithWrongKeyIsRejected() throws Exception {
        String userId = createUser("jwt-bob");
        String wrongSecret = "a-different-secret-that-is-long-enough-for-hs256";

        String token = mint(userId, "activity:read activity:write", wrongSecret, Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/activities/{id}", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String userId = createUser("jwt-carol");

        // Expired well beyond the default 60s clock skew.
        String token = mint(userId, "activity:read", jwtSecret, Instant.now().minusSeconds(600));

        mockMvc.perform(get("/api/activities/{id}", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/activities/{id}", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    private String mint(String subject, String scope, String secret, Instant expiresAt) throws Exception {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWSSigner signer = new MACSigner(key);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("scope", scope)
                .issuer("modular-monolith-test")
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(signer);
        return signedJwt.serialize();
    }
}
