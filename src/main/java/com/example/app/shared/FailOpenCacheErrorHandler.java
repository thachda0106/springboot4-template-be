package com.example.app.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Cache error handler that fails OPEN: every cache operation error is logged
 * and swallowed, so a Redis outage degrades to a cache miss (database read)
 * instead of a 500. Availability over caching — the TTL backstop and optimistic
 * locking keep stale reads safe (see docs/architecture.md "Redis").
 */
public class FailOpenCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(FailOpenCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache GET failed for cache {} key {} - failing open (DB read): {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache PUT failed for cache {} key {} - failing open (value not cached): {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache EVICT failed for cache {} key {} - failing open (stale entry expires via TTL): {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Cache CLEAR failed for cache {} - failing open: {}", cache.getName(), exception.toString());
    }
}