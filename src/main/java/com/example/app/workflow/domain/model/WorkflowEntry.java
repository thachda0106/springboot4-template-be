package com.example.app.workflow.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Workflow entry: this module's view of an activity, kept in sync through
 * events. A deliberately simple stub - in a real system the workflow would be a
 * state machine with transitions and business rules; the point of the template
 * is the decoupled, event-driven synchronization, not the workflow logic itself.
 */
public class WorkflowEntry {

    private final UUID id;
    private final UUID activityId;
    private String activityName;
    private WorkflowEntryStatus status;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    private WorkflowEntry(UUID id, UUID activityId, String activityName, WorkflowEntryStatus status,
                          Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.activityId = activityId;
        this.activityName = activityName;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Factory used when reacting to {@code ActivityCreated}. */
    public static WorkflowEntry forActivity(UUID activityId, String activityName) {
        return new WorkflowEntry(UUID.randomUUID(), activityId, activityName, WorkflowEntryStatus.CREATED,
                null, null, null);
    }

    /** Infrastructure-facing factory: reconstructs an entry from persisted state. */
    public static WorkflowEntry restore(UUID id, UUID activityId, String activityName, WorkflowEntryStatus status,
                                        Long version, Instant createdAt, Instant updatedAt) {
        return new WorkflowEntry(id, activityId, activityName, status, version, createdAt, updatedAt);
    }

    /** Synchronizes this entry with the latest state of its activity. */
    public void syncFromActivity(String activityName) {
        this.activityName = activityName;
        this.status = WorkflowEntryStatus.UPDATED;
    }

    public UUID id() {
        return id;
    }

    public UUID activityId() {
        return activityId;
    }

    public String activityName() {
        return activityName;
    }

    public WorkflowEntryStatus status() {
        return status;
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
