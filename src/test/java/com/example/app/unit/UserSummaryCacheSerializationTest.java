package com.example.app.unit;

import com.example.app.user.UserLookup;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip test of {@code Optional<UserLookup.Summary>} through the typed
 * serializer used by the {@code user-summaries} cache. {@code Summary} is a
 * record (native Jackson 3 support, no mixin). Also pins the documented
 * negative-caching behavior: {@code Optional.empty()} serializes to the bytes
 * {@code "null"} and deserializes back to {@code Optional.empty()}.
 */
class UserSummaryCacheSerializationTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final TypeFactory typeFactory = mapper.getTypeFactory();
    private final JavaType type = typeFactory.constructParametricType(Optional.class, UserLookup.Summary.class);
    private final JacksonJsonRedisSerializer<Optional<UserLookup.Summary>> serializer =
            new JacksonJsonRedisSerializer<>(mapper, type);

    @Test
    void roundTripsAPresentSummary() {
        Optional<UserLookup.Summary> original = Optional.of(new UserLookup.Summary(
                "11111111-1111-1111-1111-111111111111", "Ada"));

        byte[] bytes = serializer.serialize(original);
        Optional<UserLookup.Summary> restored = serializer.deserialize(bytes);

        assertThat(restored).isPresent();
        assertThat(restored.get().id()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(restored.get().name()).isEqualTo("Ada");
    }

    @Test
    void emptyOptionalSerializesToNullBytesAndRoundTrips() {
        byte[] bytes = serializer.serialize(Optional.empty());

        // The cache writer sees a non-null Optional, so the "null" bytes ARE stored
        // (negative caching for the TTL) - documented in docs/architecture.md "Redis".
        assertThat(new String(bytes)).isEqualTo("null");

        Optional<UserLookup.Summary> restored = serializer.deserialize(bytes);
        assertThat(restored).isEmpty();
    }
}