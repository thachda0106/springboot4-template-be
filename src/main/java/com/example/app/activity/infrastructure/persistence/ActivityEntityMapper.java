package com.example.app.activity.infrastructure.persistence;

import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;

import java.util.UUID;

/**
 * Maps between the persistence model and the domain model. The mapper is the
 * only place that knows both representations; the domain stays JPA-free.
 */
final class ActivityEntityMapper {

    private ActivityEntityMapper() {
    }

    static ActivityJpaEntity toEntity(Activity activity) {
        return new ActivityJpaEntity(
                activity.id().value(),
                activity.name(),
                activity.description(),
                activity.status(),
                UUID.fromString(activity.createdBy()),
                activity.version(),
                activity.createdAt(),
                activity.updatedAt());
    }

    static Activity toDomain(ActivityJpaEntity entity) {
        return Activity.restore(
                ActivityId.from(entity.getId()),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedBy().toString(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
