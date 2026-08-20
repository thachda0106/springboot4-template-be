package com.example.app.user.application.config;

import com.example.app.shared.RedisCacheConfigurer;
import com.example.app.user.UserLookup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.util.Optional;

/**
 * Contributes the {@code user-summaries} cache configuration to the shared
 * {@code RedisCacheManager}. The cache stores {@code Optional<UserLookup.Summary>}
 * — a record, natively supported by Jackson 3 (no mixin needed). The serializer
 * is typed to {@code Optional<Summary>} via the type factory.
 */
@Configuration(proxyBeanMethods = false)
public class UserSummaryCacheConfiguration {

    @Bean
    RedisCacheConfigurer userSummaryCacheConfigurer(RedisCacheConfiguration cacheDefaults) {
        ObjectMapper mapper = JsonMapper.builder().build();
        TypeFactory typeFactory = mapper.getTypeFactory();
        JavaType type = typeFactory.constructParametricType(Optional.class, UserLookup.Summary.class);
        RedisCacheConfiguration configuration = cacheDefaults.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(mapper, type)));
        return new RedisCacheConfigurer() {
            @Override
            public String cacheName() {
                return "user-summaries";
            }

            @Override
            public RedisCacheConfiguration configuration() {
                return configuration;
            }
        };
    }
}