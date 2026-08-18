package com.example.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security matrix: 401 for unauthenticated, 403 for insufficient authority,
 * 200 for valid authentication, and the public actuator health endpoint.
 */
class SecurityIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorPrometheusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedActivityReadReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/activities/{id}", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/activities/00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void unauthenticatedWorkflowReadReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/workflow-entries/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unauthenticatedUserCreateReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alice", "email": "a@example.com", "password": "s3cret-pass"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteActivityWithoutAdminRoleReturns403() throws Exception {
        mockMvc.perform(delete("/api/v1/activities/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void writeActivityWithoutAnyRoleReturns403() throws Exception {
        // A token with no role claim is authenticated but has no ROLE_* authority.
        mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject("user-x")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeActivityWithUnknownRoleReturns403() throws Exception {
        // An unknown role maps to no ROLE_* authority (closed allow-list); here the
        // token carries only ROLE_SUPERUSER, which grants nothing on this endpoint.
        mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "SUPERUSER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPERUSER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyAuthenticatedUserCanReadActivities() throws Exception {
        mockMvc.perform(get("/api/v1/activities/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                // authentication passed (role only guards the write endpoints);
                // the id does not exist -> 404, NOT 401/403
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }

    @Test
    void unknownPathReturns404JsonForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist")
                        .with(jwt().jwt(j -> j.subject("user-x").claim("role", "USER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
