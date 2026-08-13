package com.example.app.workflow.api.dto;

import com.example.app.workflow.domain.model.WorkflowEntry;

import java.time.Instant;
import java.util.UUID;

/** REST representation of a workflow entry. JPA entities are never exposed directly. */
public record WorkflowEntryResponse(
        UUID id,
        UUID activityId,
        String activityName,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkflowEntryResponse from(WorkflowEntry entry) {
        return new WorkflowEntryResponse(
                entry.id(),
                entry.activityId(),
                entry.activityName(),
                entry.status().name(),
                entry.version() == null ? 0L : entry.version(),
                entry.createdAt(),
                entry.updatedAt());
    }
}
