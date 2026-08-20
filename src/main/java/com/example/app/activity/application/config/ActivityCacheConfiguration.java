package com.example.app.activity.application.config;

import com.example.app.activity.domain.model.Activity;
import com.example.app.shared.RedisCacheConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contributes the {@code activities} cache configuration to the shared
 * {@code RedisCacheManager}. The cache stores {@link Activity} domain objects,
 * serialized with a typed {@link JacksonJsonRedisSerializer} backed by a
 * module-local {@code ObjectMapper} with {@link ActivityCacheMixin} — the
 * domain class itself stays annotation-free.
 */
@Configuration(proxyBeanMethods = false)
public class ActivityCacheConfiguration {

    @Bean
    RedisCacheConfigurer activityCacheConfigurer(RedisCacheConfiguration cacheDefaults) {
        ObjectMapper mapper = JsonMapper.builder()
                .addMixIn(Activity.class, ActivityCacheMixin.class)
                .build();
        RedisCacheConfiguration configuration = cacheDefaults.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(mapper, Activity.class)));
        return new RedisCacheConfigurer() {
            @Override
            public String cacheName() {
                return "activities";
            }

            @Override
            public RedisCacheConfiguration configuration() {
                return configuration;
            }
        };
    }
}