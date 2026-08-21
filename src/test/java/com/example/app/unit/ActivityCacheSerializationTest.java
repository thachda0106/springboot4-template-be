package com.example.app.unit;

import com.example.app.activity.infrastructure.cache.ActivityCacheMixin;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.model.ActivityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip serialization of {@link Activity} through the module-local mixin
 * serializer (the same mapper the {@code activities} cache uses). Proves the
 * mixin's property names match the {@code restore(...)} parameters (symmetry),
 * so cached values deserialize back to the full domain object.
 */
class ActivityCacheSerializationTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addMixIn(Activity.class, ActivityCacheMixin.class)
            .build();
    private final JacksonJsonRedisSerializer<Activity> serializer =
            new JacksonJsonRedisSerializer<>(mapper, Activity.class);

    @Test
    void roundTripsAnActivity() {
        Activity original = Activity.restore(
                ActivityId.from(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                "Morning run", "5k along the river", ActivityStatus.ACTIVE,
                "22222222-2222-2222-2222-222222222222", 7L,
                Instant.parse("2026-08-20T10:00:00Z"), Instant.parse("2026-08-20T11:30:00Z"));

        byte[] bytes = serializer.serialize(original);
        Activity restored = serializer.deserialize(bytes);

        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.name()).isEqualTo(original.name());
        assertThat(restored.description()).isEqualTo(original.description());
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.createdBy()).isEqualTo(original.createdBy());
        assertThat(restored.version()).isEqualTo(original.version());
        assertThat(restored.createdAt()).isEqualTo(original.createdAt());
        assertThat(restored.updatedAt()).isEqualTo(original.updatedAt());
    }
}