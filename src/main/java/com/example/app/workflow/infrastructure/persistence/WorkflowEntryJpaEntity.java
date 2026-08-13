package com.example.app.workflow.infrastructure.persistence;

import com.example.app.workflow.domain.model.WorkflowEntryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA representation of a workflow entry - internal persistence detail of the
 * workflow module. One row per activity (unique activity_id).
 */
@Entity
@Table(name = "workflow_entries")
public class WorkflowEntryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "activity_id", nullable = false, unique = true)
    private UUID activityId;

    @Column(name = "activity_name", nullable = false, length = 200)
    private String activityName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkflowEntryStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowEntryJpaEntity() {
        // for JPA
    }

    public WorkflowEntryJpaEntity(UUID id, UUID activityId, String activityName, WorkflowEntryStatus status,
                                  Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.activityId = activityId;
        this.activityName = activityName;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public String getActivityName() {
        return activityName;
    }

    public WorkflowEntryStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public void setStatus(WorkflowEntryStatus status) {
        this.status = status;
    }
}
