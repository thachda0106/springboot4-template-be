package com.example.app.unit;

import com.example.app.security.web.RedisFixedWindowRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis fixed-window limiter: window counting, fail-open on
 * connection failure AND command timeout ({@link RedisSystemException}), and
 * fail-closed propagation. No Spring context, no Docker.
 */
class RedisFixedWindowRateLimiterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RedisFixedWindowRateLimiter limiter(StringRedisTemplate template, boolean failOpen) {
        return new RedisFixedWindowRateLimiter("test", 3, Duration.ofSeconds(1),
                failOpen, template, meterRegistry);
    }

    @Test
    void allowsUpToTheLimit() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L, 2L, 3L);

        RedisFixedWindowRateLimiter limiter = limiter(template, true);

        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(limiter.allow("1.2.3.4")).isTrue();
    }

    @Test
    void rejectsPastTheLimit() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L, 2L, 3L, 4L);

        RedisFixedWindowRateLimiter limiter = limiter(template, true);

        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(limiter.allow("1.2.3.4")).isFalse();
    }

    @Test
    void failsOpenOnConnectionFailure() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        RedisFixedWindowRateLimiter limiter = limiter(template, true);

        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(meterRegistry.counter("app.security.limiter.failopen", "layer", "test").count())
                .isEqualTo(1);
    }

    @Test
    void failsOpenOnCommandTimeout() {
        // A slow/hung Redis raises RedisSystemException (Lettuce command timeout) -
        // the fail-open contract must cover this case, not just connection-refused.
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisSystemException("command timed out", new RuntimeException("timeout")));

        RedisFixedWindowRateLimiter limiter = limiter(template, true);

        assertThat(limiter.allow("1.2.3.4")).isTrue();
        assertThat(meterRegistry.counter("app.security.limiter.failopen", "layer", "test").count())
                .isEqualTo(1);
    }

    @Test
    void propagatesWhenFailClosed() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        RedisFixedWindowRateLimiter limiter = limiter(template, false);

        assertThatThrownBy(() -> limiter.allow("1.2.3.4"))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}