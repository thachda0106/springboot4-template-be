package com.example.app.workflow.infrastructure.persistence;

import com.example.app.workflow.domain.model.WorkflowEntry;

/**
 * Maps between the persistence model and the domain model.
 * The only place in the workflow module that knows both representations.
 */
final class WorkflowEntryEntityMapper {

    private WorkflowEntryEntityMapper() {
    }

    static WorkflowEntryJpaEntity toEntity(WorkflowEntry entry) {
        return new WorkflowEntryJpaEntity(
                entry.id(),
                entry.activityId(),
                entry.activityName(),
                entry.status(),
                entry.version(),
                entry.createdAt(),
                entry.updatedAt());
    }

    static WorkflowEntry toDomain(WorkflowEntryJpaEntity entity) {
        return WorkflowEntry.restore(
                entity.getId(),
                entity.getActivityId(),
                entity.getActivityName(),
                entity.getStatus(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
