package com.example.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI / Swagger UI documentation: the generated spec must contain the
 * resolved (prefixed) API paths and the JWT bearer security scheme, and the
 * Swagger UI must be served.
 */
class ApiDocsIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void specContainsApiV1PathsAndBearerScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Global /api/v1 prefix (ApiPathPrefixConfig) must appear in the resolved paths
                .andExpect(jsonPath("$.paths.['/api/v1/activities']").exists())
                .andExpect(jsonPath("$.paths.['/api/v1/activities/{id}']").exists())
                .andExpect(jsonPath("$.paths.['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths.['/api/v1/auth/refresh']").exists())
                .andExpect(jsonPath("$.paths.['/api/v1/workflow-entries/{activityId}']").exists())
                // JWT bearer security scheme usable by the Swagger UI "Authorize" button
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                // Global security requirement on operations
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void yamlSpecIsServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk());
    }
}
