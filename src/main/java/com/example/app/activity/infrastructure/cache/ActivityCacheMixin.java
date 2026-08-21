package com.example.app.activity.infrastructure.cache;

import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.model.ActivityStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Jackson mixin for {@link Activity}, registered on the activity module's own
 * {@code ObjectMapper} in {@link ActivityCacheConfiguration}.
 *
 * <p>{@code Activity} is a deliberately plain domain object: record-style
 * accessors ({@code name()}, not {@code getName()}) and a private constructor.
 * Jackson 3 does not auto-detect record-style accessors on regular classes, so
 * the mixin declares the property shape: {@code @JsonProperty} on the accessors
 * and a {@code @JsonCreator} static factory mirroring {@link Activity#restore}.
 * The mixin body is never invoked — Jackson applies the annotations to the
 * matching {@code Activity} members. The domain class stays annotation-free.
 * Public only so the serialization unit tests can register it; it lives in a
 * sub-package, so it is not part of the module's public API.
 */
public abstract class ActivityCacheMixin {

    @JsonProperty
    abstract ActivityId id();

    @JsonProperty
    abstract String name();

    @JsonProperty
    abstract String description();

    @JsonProperty
    abstract ActivityStatus status();

    @JsonProperty
    abstract String createdBy();

    @JsonProperty
    abstract Long version();

    @JsonProperty
    abstract Instant createdAt();

    @JsonProperty
    abstract Instant updatedAt();

    @JsonCreator
    static Activity restore(
            @JsonProperty("id") ActivityId id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("status") ActivityStatus status,
            @JsonProperty("createdBy") String createdBy,
            @JsonProperty("version") Long version,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt) {
        // Never invoked: the annotations are applied to Activity.restore(...).
        return null;
    }
}