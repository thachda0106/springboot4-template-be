package com.example.app.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end cache behavior against real Redis: the three caches
 * ({@code activities}, {@code workflow-entries}, {@code user-summaries}) are
 * populated on read and evicted on write.
 */
class CacheIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void activityReadIsCachedAndEvictedOnUpdate() throws Exception {
        String userId = createUser("cache-alice");
        String activityId = createActivity(userId, "Retro");
        String key = "activities:" + activityId;

        // First GET: miss -> DB, populates the cache.
        mockMvc.perform(get("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retro"));
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // Second GET: served from the cache (key still present, value unchanged).
        mockMvc.perform(get("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retro"));
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // Update evicts the key.
        mockMvc.perform(put("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro Q2", "version": 0}
                                """))
                .andExpect(status().isOk());
        assertThat(redisTemplate.hasKey(key)).isFalse();

        // Next GET repopulates with the new value.
        mockMvc.perform(get("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Retro Q2"));
        assertThat(redisTemplate.hasKey(key)).isTrue();
    }

    @Test
    void workflowEntryIsCachedAndEvictedOnActivityUpdate() throws Exception {
        String userId = createUser("cache-bob");
        String activityId = createActivity(userId, "Retro");
        String key = "workflow-entries:" + activityId;

        // The event listener runs synchronously after commit, so the entry exists.
        mockMvc.perform(get("/api/v1/workflow-entries/{activityId}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityName").value("Retro"));
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // Updating the activity evicts the workflow-entries key (listener path).
        mockMvc.perform(put("/api/v1/activities/{id}", activityId)
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Retro Q2", "version": 0}
                                """))
                .andExpect(status().isOk());
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void userSummaryIsCachedWhenActivityIsCreated() throws Exception {
        String userId = createUser("cache-carol");
        String key = "user-summaries:" + userId;
        assertThat(redisTemplate.hasKey(key)).isFalse();

        // Creating an activity resolves the creator through UserLookup -> populates the cache.
        createActivity(userId, "Retro");
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // Creating a user evicts its own (never-cached) summary - no stale entry.
        String newUserId = createUser("cache-dave");
        assertThat(redisTemplate.hasKey("user-summaries:" + newUserId)).isFalse();
    }

    private String createActivity(String userId, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/activities")
                        .with(jwt().jwt(j -> j.subject(userId).claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}