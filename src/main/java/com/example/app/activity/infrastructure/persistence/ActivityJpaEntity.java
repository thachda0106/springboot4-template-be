package com.example.app.activity.infrastructure.persistence;

import com.example.app.activity.domain.model.ActivityStatus;
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
 * JPA representation of an activity. Lives in the infrastructure layer on purpose:
 * the domain model stays persistence-independent and this class is never exposed
 * outside the module's infrastructure package.
 *
 * <p>{@code version} is the optimistic-lock column: Hibernate compares it on every
 * merge/flush and throws {@code ObjectOptimisticLockingFailureException} on a
 * concurrent write, which is mapped to HTTP 409.
 */
@Entity
@Table(name = "activities")
public class ActivityJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActivityJpaEntity() {
        // for JPA
    }

    public ActivityJpaEntity(UUID id, String name, String description, ActivityStatus status,
                             UUID createdBy, Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.status = status;
        this.createdBy = createdBy;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ActivityStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
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

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStatus(ActivityStatus status) {
        this.status = status;
    }
}
