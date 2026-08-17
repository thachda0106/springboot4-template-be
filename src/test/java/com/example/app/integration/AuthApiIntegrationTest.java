package com.example.app.integration;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the authentication flow: login, refresh rotation, logout
 * revocation, RBAC, and the security edge cases (inactive/legacy users, concurrency,
 * oversized passwords).
 */
class AuthApiIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String createUserWithPassword(String name, String email, String password, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "email": "%s", "password": "%s", "role": "%s"}
                                """.formatted(name, email, password, role)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void loginWithValidCredentialsReturnsTokenPair() throws Exception {
        String email = "alice." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Alice", email, "s3cret-pass", "USER");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "s3cret-pass"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = "bob." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Bob", email, "s3cret-pass", "USER");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong-pass"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginWithUnknownEmailReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ghost.%s@example.com", "password": "whatever"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginWithInactiveUserReturns401() throws Exception {
        String email = "inactive." + UUID.randomUUID() + "@example.com";
        String userId = createUserWithPassword("Inactive", email, "s3cret-pass", "USER");
        jdbcTemplate.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", UUID.fromString(userId));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "s3cret-pass"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginWithLegacyNullHashReturns401() throws Exception {
        // Simulates a row upgraded from the pre-credential schema: null password_hash,
        // role defaulted to USER. Login must be a uniform 401, not a 500.
        String email = "legacy." + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, status, created_at, updated_at)
                VALUES (?, 'Legacy', ?, 'ACTIVE', now(), now())
                """, UUID.randomUUID(), email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "whatever"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshRotatesTokenAndOldRefreshIsRejected() throws Exception {
        String email = "carol." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Carol", email, "s3cret-pass", "USER");
        String refreshToken = login(email, "s3cret-pass").get("refreshToken").asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String newRefreshToken = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        // The old refresh token is now consumed -> 401.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String email = "dave." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Dave", email, "s3cret-pass", "USER");
        JsonNode login = login(email, "s3cret-pass");
        String accessToken = login.get("accessToken").asText();
        String refreshToken = login.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isNoContent());

        // Subsequent refresh with the revoked token -> 401.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenRemainsValidUntilExpiryAfterLogout() throws Exception {
        // Documented stateless behavior: logout revokes the refresh token only;
        // the access token stays valid until exp.
        String email = "erin." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Erin", email, "s3cret-pass", "USER");
        JsonNode login = login(email, "s3cret-pass");
        String accessToken = login.get("accessToken").asText();
        String refreshToken = login.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void logoutWithoutAccessTokenReturns401() throws Exception {
        // The refresh token alone is not enough to log out; the endpoint is authenticated.
        String email = "frank." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Frank", email, "s3cret-pass", "USER");
        String refreshToken = login(email, "s3cret-pass").get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveUserCannotRefresh() throws Exception {
        String email = "grace." + UUID.randomUUID() + "@example.com";
        String userId = createUserWithPassword("Grace", email, "s3cret-pass", "USER");
        String refreshToken = login(email, "s3cret-pass").get("refreshToken").asText();
        jdbcTemplate.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", UUID.fromString(userId));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void userRoleCannotDeleteActivityOrCreateUser() throws Exception {
        String email = "henry." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Henry", email, "s3cret-pass", "USER");
        String accessToken = login(email, "s3cret-pass").get("accessToken").asText();

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "X", "email": "x.%s@example.com", "password": "s3cret-pass"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/activities/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleCanCreateUser() throws Exception {
        String email = "iris." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Iris", email, "s3cret-pass", "ADMIN");
        String accessToken = login(email, "s3cret-pass").get("accessToken").asText();

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New", "email": "new.%s@example.com", "password": "s3cret-pass", "role": "USER"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentRefreshYieldsExactlyOneSuccessor() throws Exception {
        String email = "jack." + UUID.randomUUID() + "@example.com";
        createUserWithPassword("Jack", email, "s3cret-pass", "USER");
        String refreshToken = login(email, "s3cret-pass").get("refreshToken").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> refresh = () -> {
            start.await();
            return mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken": "%s"}
                                    """.formatted(refreshToken)))
                    .andReturn().getResponse().getStatus();
        };
        Future<Integer> f1 = pool.submit(refresh);
        Future<Integer> f2 = pool.submit(refresh);
        start.countDown();
        int s1 = f1.get(10, TimeUnit.SECONDS);
        int s2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // The atomic consume serializes the race: exactly one refresh wins.
        assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void loginWithOversizedPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "a@b.com", "password": "%s"}
                                """.formatted("x".repeat(100))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginWithMultibyteOversizedPasswordReturns400() throws Exception {
        // 40 emoji = 40 chars (passes @Size(max=72)) but 160 UTF-8 bytes (fails the
        // BCrypt byte limit in the use case).
        String password = "😀".repeat(40);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "a@b.com", "password": "%s"}
                                """.formatted(password)))
                .andExpect(status().isBadRequest());
    }
}
