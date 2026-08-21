package com.example.app.workflow.infrastructure.cache;

import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.model.WorkflowEntryStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Jackson mixin for {@link WorkflowEntry}, registered on the workflow module's
 * own {@code ObjectMapper} in {@link WorkflowEntryCacheConfiguration}.
 *
 * <p>{@code WorkflowEntry} is a deliberately plain domain object: record-style
 * accessors ({@code activityName()}, not {@code getActivityName()}) and a
 * private constructor. Jackson 3 does not auto-detect record-style accessors on
 * regular classes, so the mixin declares the property shape: {@code @JsonProperty}
 * on the accessors and a {@code @JsonCreator} static factory mirroring
 * {@link WorkflowEntry#restore}. The mixin body is never invoked — Jackson
 * applies the annotations to the matching {@code WorkflowEntry} members. The
 * domain class stays annotation-free. Public only so the serialization unit
 * tests can register it; it lives in a sub-package, so it is not part of the
 * module's public API.
 */
public abstract class WorkflowEntryCacheMixin {

    @JsonProperty
    abstract UUID id();

    @JsonProperty
    abstract UUID activityId();

    @JsonProperty
    abstract String activityName();

    @JsonProperty
    abstract WorkflowEntryStatus status();

    @JsonProperty
    abstract Long version();

    @JsonProperty
    abstract Instant createdAt();

    @JsonProperty
    abstract Instant updatedAt();

    @JsonCreator
    static WorkflowEntry restore(
            @JsonProperty("id") UUID id,
            @JsonProperty("activityId") UUID activityId,
            @JsonProperty("activityName") String activityName,
            @JsonProperty("status") WorkflowEntryStatus status,
            @JsonProperty("version") Long version,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt) {
        // Never invoked: the annotations are applied to WorkflowEntry.restore(...).
        return null;
    }
}