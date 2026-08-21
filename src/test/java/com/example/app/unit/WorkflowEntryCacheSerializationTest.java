package com.example.app.unit;

import com.example.app.workflow.infrastructure.cache.WorkflowEntryCacheMixin;
import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.model.WorkflowEntryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip test of {@link WorkflowEntry} through the module-local mixin
 * serializer (the same mapper the {@code workflow-entries} cache uses). Asserts
 * property symmetry between the accessors and the {@code restore(...)} factory.
 */
class WorkflowEntryCacheSerializationTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addMixIn(WorkflowEntry.class, WorkflowEntryCacheMixin.class)
            .build();
    private final JacksonJsonRedisSerializer<WorkflowEntry> serializer =
            new JacksonJsonRedisSerializer<>(mapper, WorkflowEntry.class);

    @Test
    void roundTripsAWorkflowEntry() {
        WorkflowEntry original = WorkflowEntry.restore(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "My run", WorkflowEntryStatus.UPDATED, 3L,
                Instant.parse("2026-08-20T10:00:00Z"), Instant.parse("2026-08-20T11:30:00Z"));

        byte[] bytes = serializer.serialize(original);
        WorkflowEntry restored = serializer.deserialize(bytes);

        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.activityId()).isEqualTo(original.activityId());
        assertThat(restored.activityName()).isEqualTo(original.activityName());
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.version()).isEqualTo(original.version());
        assertThat(restored.createdAt()).isEqualTo(original.createdAt());
        assertThat(restored.updatedAt()).isEqualTo(original.updatedAt());
    }
}