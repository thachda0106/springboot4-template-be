package com.example.app.activity.api.dto;

import com.example.app.activity.domain.model.Activity;

import java.time.Instant;

/** REST representation of an activity. JPA entities are never exposed directly. */
public record ActivityResponse(
        String id,
        String name,
        String description,
        String status,
        String createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.id().value().toString(),
                activity.name(),
                activity.description(),
                activity.status().name(),
                activity.createdBy(),
                activity.version() == null ? 0L : activity.version(),
                activity.createdAt(),
                activity.updatedAt());
    }
}
