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
public class WorkflowEntryRepositoryAdapter implements WorkflowEntryRepository {

    private final SpringDataWorkflowEntryRepository springDataRepository;

    public WorkflowEntryRepositoryAdapter(SpringDataWorkflowEntryRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public WorkflowEntry save(WorkflowEntry entry) {
        // saveAndFlush: see ActivityRepositoryAdapter for the rationale.
        return WorkflowEntryEntityMapper.toDomain(
                springDataRepository.saveAndFlush(WorkflowEntryEntityMapper.toEntity(entry)));
    }

    @Override
    public Optional<WorkflowEntry> findByActivityId(UUID activityId) {
        return springDataRepository.findByActivityId(activityId).map(WorkflowEntryEntityMapper::toDomain);
    }

    @Override
    public void delete(WorkflowEntry entry) {
        springDataRepository.deleteById(entry.id());
    }
}
