package com.example.app.workflow.infrastructure.cache;

import com.example.app.shared.RedisCacheConfigurer;
import com.example.app.workflow.domain.model.WorkflowEntry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contributes the {@code workflow-entries} cache configuration to the shared
 * {@code RedisCacheManager}. The cache stores {@link WorkflowEntry} domain
 * objects, serialized with a typed {@link JacksonJsonRedisSerializer} backed by
 * a module-local {@code ObjectMapper} with {@link WorkflowEntryCacheMixin} —
 * the domain class itself stays annotation-free.
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowEntryCacheConfiguration {

    @Bean
    RedisCacheConfigurer workflowEntryCacheConfigurer(RedisCacheConfiguration cacheDefaults) {
        ObjectMapper mapper = JsonMapper.builder()
                .addMixIn(WorkflowEntry.class, WorkflowEntryCacheMixin.class)
                .build();
        RedisCacheConfiguration configuration = cacheDefaults.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(mapper, WorkflowEntry.class)));
        return new RedisCacheConfigurer() {
            @Override
            public String cacheName() {
                return "workflow-entries";
            }

            @Override
            public RedisCacheConfiguration configuration() {
                return configuration;
            }
        };
    }
}