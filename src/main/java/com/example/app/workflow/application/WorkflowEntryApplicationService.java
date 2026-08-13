package com.example.app.workflow.application;

import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.repository.WorkflowEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies activity lifecycle events to this module's state.
 *
 * <p>All operations are <em>idempotent</em>: if an event is processed twice
 * (possible with at-least-once delivery after the future Kafka evolution),
 * the result is identical. The workflow module therefore never needs an inbox
 * for these simple sync operations - see docs/event-driven.md.
 *
 * <p>Each method runs in its own transaction (the event listener itself runs
 * after the publisher's commit, in a REQUIRES_NEW transaction).
 */
@Service
public class WorkflowEntryApplicationService {

    private final WorkflowEntryRepository workflowEntryRepository;

    public WorkflowEntryApplicationService(WorkflowEntryRepository workflowEntryRepository) {
        this.workflowEntryRepository = workflowEntryRepository;
    }

    @Transactional
    public void onActivityCreated(UUID activityId, String activityName) {
        if (workflowEntryRepository.findByActivityId(activityId).isPresent()) {
            return; // duplicate delivery - already processed
        }
        workflowEntryRepository.save(WorkflowEntry.forActivity(activityId, activityName));
    }

    @Transactional
    public void onActivityUpdated(UUID activityId, String activityName) {
        WorkflowEntry entry = workflowEntryRepository.findByActivityId(activityId).orElse(null);
        if (entry == null) {
            // Update arrived before the create event (out-of-order delivery):
            // reconstruct the entry instead of dropping the update.
            entry = workflowEntryRepository.save(WorkflowEntry.forActivity(activityId, activityName));
        }
        entry.syncFromActivity(activityName);
        workflowEntryRepository.save(entry);
    }

    @Transactional
    public void onActivityDeleted(UUID activityId) {
        workflowEntryRepository.findByActivityId(activityId).ifPresent(workflowEntryRepository::delete);
    }
}
