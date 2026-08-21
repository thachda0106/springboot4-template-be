package com.example.app.shared.cache;

import com.example.app.shared.RedisCacheConfigurer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;

/**
 * Redis-backed Spring Cache infrastructure.
 *
 * <p>Builds the application-wide {@link RedisCacheManager} from the shared
 * defaults ({@link CacheDefaultsConfig#cacheDefaults()}) plus the per-cache
 * configurations contributed by the owning business modules through
 * {@link RedisCacheConfigurer} (each cache stores a different domain type, so
 * each needs its own typed serializer — see the module-local
 * {@code *CacheConfiguration} classes).
 *
 * <p>Fails OPEN: {@link #errorHandler()} installs {@link FailOpenCacheErrorHandler},
 * so a Redis outage degrades to cache misses (database reads) instead of 500s.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private final List<RedisCacheConfigurer> cacheConfigurers;
    private final RedisConnectionFactory connectionFactory;

    public CacheConfig(List<RedisCacheConfigurer> cacheConfigurers,
                       RedisConnectionFactory connectionFactory) {
        this.cacheConfigurers = cacheConfigurers;
        this.connectionFactory = connectionFactory;
    }

    @Bean
    CacheManager cacheManager(RedisCacheConfiguration cacheDefaults) {
        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheDefaults);
        for (RedisCacheConfigurer configurer : cacheConfigurers) {
            builder.withCacheConfiguration(configurer.cacheName(), configurer.configuration());
        }
        return builder.build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new FailOpenCacheErrorHandler();
    }
}