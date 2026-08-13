package com.example.app.workflow.domain.repository;

import com.example.app.workflow.domain.model.WorkflowEntry;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository contract for workflow entries.
 * Implemented in the infrastructure layer.
 */
public interface WorkflowEntryRepository {

    WorkflowEntry save(WorkflowEntry entry);

    Optional<WorkflowEntry> findByActivityId(UUID activityId);

    void delete(WorkflowEntry entry);
}
