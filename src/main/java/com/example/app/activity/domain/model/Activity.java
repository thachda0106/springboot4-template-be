package com.example.app.activity.domain.model;

import com.example.app.activity.domain.exception.InvalidActivityException;

import java.time.Instant;

/**
 * Activity aggregate root.
 *
 * <p>A plain domain object: no JPA annotations, no Spring, no HTTP, no security.
 * Business invariants (non-blank name, no updates on archived state) live here.
 * Persistence goes through {@code ActivityRepository}, implemented in the
 * infrastructure layer. The {@code version} field is the optimistic-lock token
 * enforced by the persistence layer.
 */
public class Activity {

    private final ActivityId id;
    private String name;
    private String description;
    private ActivityStatus status;
    private final String createdBy;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    private Activity(ActivityId id, String name, String description, ActivityStatus status,
                     String createdBy, Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.status = status;
        this.createdBy = createdBy;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Factory used by the application layer when creating a new activity. */
    public static Activity create(String name, String description, String createdBy) {
        if (name == null || name.isBlank()) {
            throw new InvalidActivityException("Activity name must not be blank");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new InvalidActivityException("Activity creator must not be blank");
        }
        return new Activity(ActivityId.random(), name.trim(), description, ActivityStatus.DRAFT, createdBy,
                null, null, null);
    }

    /** Infrastructure-facing factory: reconstructs an activity from persisted state. */
    public static Activity restore(ActivityId id, String name, String description, ActivityStatus status,
                                   String createdBy, Long version, Instant createdAt, Instant updatedAt) {
        return new Activity(id, name, description, status, createdBy, version, createdAt, updatedAt);
    }

    /** Applies an update; a created activity becomes active on its first update. */
    public void update(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new InvalidActivityException("Activity name must not be blank");
        }
        this.name = name.trim();
        this.description = description;
        this.status = ActivityStatus.ACTIVE;
    }

    public ActivityId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public ActivityStatus status() {
        return status;
    }

    public String createdBy() {
        return createdBy;
    }

    public Long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
