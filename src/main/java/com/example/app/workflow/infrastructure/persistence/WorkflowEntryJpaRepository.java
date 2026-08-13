package com.example.app.workflow.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository - internal persistence detail of the workflow module.
 * Only {@link JpaWorkflowEntryRepository} may use it.
 */
interface WorkflowEntryJpaRepository extends JpaRepository<WorkflowEntryJpaEntity, UUID> {

    Optional<WorkflowEntryJpaEntity> findByActivityId(UUID activityId);
}
