package com.example.app.unit;

import com.example.app.shared.FailOpenCacheErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for the fail-open cache error handler: every cache operation error
 * is logged and swallowed (a Redis outage degrades to a cache miss, never a 500).
 */
class FailOpenCacheErrorHandlerTest {

    private final CacheErrorHandler handler = new FailOpenCacheErrorHandler();
    private final Cache cache = mock(Cache.class);

    @Test
    void getErrorsAreSwallowed() {
        handler.handleCacheGetError(new RuntimeException("boom"), cache, "key");
    }

    @Test
    void putErrorsAreSwallowed() {
        handler.handleCachePutError(new RuntimeException("boom"), cache, "key", "value");
    }

    @Test
    void evictErrorsAreSwallowed() {
        handler.handleCacheEvictError(new RuntimeException("boom"), cache, "key");
    }

    @Test
    void clearErrorsAreSwallowed() {
        handler.handleCacheClearError(new RuntimeException("boom"), cache);
    }
}