package com.example.app.workflow.infrastructure.persistence;

import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.repository.WorkflowEntryRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the workflow domain repository contract.
 * The only class in the workflow module allowed to touch Spring Data.
 */
@Repository
public class JpaWorkflowEntryRepository implements WorkflowEntryRepository {

    private final WorkflowEntryJpaRepository jpaRepository;

    public JpaWorkflowEntryRepository(WorkflowEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WorkflowEntry save(WorkflowEntry entry) {
        // saveAndFlush: see JpaActivityRepository for the rationale.
        return WorkflowEntryEntityMapper.toDomain(
                jpaRepository.saveAndFlush(WorkflowEntryEntityMapper.toEntity(entry)));
    }

    @Override
    public Optional<WorkflowEntry> findByActivityId(UUID activityId) {
        return jpaRepository.findByActivityId(activityId).map(WorkflowEntryEntityMapper::toDomain);
    }

    @Override
    public void delete(WorkflowEntry entry) {
        jpaRepository.deleteById(entry.id());
    }
}
