package com.example.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end REST + persistence + security tests for the activity module.
 */
class ActivityApiIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void createActivityReturns201WithLocationAndBody() throws Exception {
        String userId = createUser("alice");

        mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Team retro", "description": "Monthly retro"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Team retro"))
                .andExpect(jsonPath("$.description").value("Monthly retro"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy").value(userId))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void createActivityWithoutRoleReturns403() throws Exception {
        String userId = createUser("bob");

        // An authenticated token with no ROLE_* authority cannot create activities.
        mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Team retro"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void createActivityWithBlankNameReturns400WithFieldErrors() throws Exception {
        String userId = createUser("carol");

        mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void createActivityForUnknownCreatorReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Team retro"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREATOR_NOT_FOUND"));
    }

    @Test
    void getActivityReturns200() throws Exception {
        String userId = createUser("dave");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(get("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activityId))
                .andExpect(jsonPath("$.name").value("Retro"));
    }

    @Test
    void getUnknownActivityReturns404() throws Exception {
        String userId = createUser("erin");

        mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }

    @Test
    void updateActivityReturns200AndActivatesIt() throws Exception {
        String userId = createUser("frank");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(put("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro Q3", "description": "updated", "version": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retro Q3"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void updateWithStaleVersionReturns409() throws Exception {
        String userId = createUser("grace");
        String activityId = createActivity(userId, "Retro");

        // First update succeeds (version 0 -> 1) ...
        mockMvc.perform(put("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro Q3", "version": 0}
                                """))
                .andExpect(status().isOk());

        // ... the same version 0 is now stale -> 409
        mockMvc.perform(put("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro Q4", "version": 0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void deleteWithoutAdminScopeReturns403() throws Exception {
        String userId = createUser("heidi");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(delete("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteWithAdminScopeReturns204AndRemovesTheActivity() throws Exception {
        String userId = createUser("ivan");
        String activityId = createActivity(userId, "Retro");

        mockMvc.perform(delete("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "ADMIN")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String createActivity(String userId, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
