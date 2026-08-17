package com.example.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end REST + persistence + security tests for the user module.
 * User creation now requires ROLE_ADMIN and an initial password.
 */
class UserApiIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void createUserReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alice Nguyen", "email": "alice.%s@example.com", "password": "s3cret-pass", "role": "USER"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Alice Nguyen"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.email").isNotEmpty());
    }

    @Test
    void createUserWithoutAdminRoleReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alice", "email": "alice.%s@example.com", "password": "s3cret-pass"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUserWithInvalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alice", "email": "not-an-email", "password": "s3cret-pass"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    }

    @Test
    void createUserWithDuplicateEmailReturns409() throws Exception {
        String email = "duplicate." + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alice", "email": "%s", "password": "s3cret-pass"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bob", "email": "%s", "password": "s3cret-pass"}
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
    }

    @Test
    void getUnknownUserReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("someone").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        String userId = createUser("self");

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.name").value("self"));
    }
}
