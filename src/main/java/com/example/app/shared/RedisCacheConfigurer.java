package com.example.app.shared;

import org.springframework.data.redis.cache.RedisCacheConfiguration;

/**
 * Contributor contract for per-cache {@link RedisCacheConfiguration}s.
 *
 * <p>The shared cache infrastructure ({@link CacheConfig}) builds one
 * {@code RedisCacheManager} for the whole application, but each cache stores a
 * different value type (a domain object per business module). The per-cache
 * serializers reference business types, so the configurations are contributed
 * by the owning modules and collected here — {@code shared} never imports
 * business types.
 */
public interface RedisCacheConfigurer {

    /** Cache name this configurer contributes (e.g. {@code activities}). */
    String cacheName();

    /** Cache configuration (serializer, TTL, ...) for {@link #cacheName()}. */
    RedisCacheConfiguration configuration();
}