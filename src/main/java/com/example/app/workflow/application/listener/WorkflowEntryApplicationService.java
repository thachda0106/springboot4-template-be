package com.example.app.workflow.application.listener;

import com.example.app.shared.AfterCommitMetrics;
import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.repository.WorkflowEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.annotation.CacheEvict;
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
    private final MeterRegistry meterRegistry;

    public WorkflowEntryApplicationService(WorkflowEntryRepository workflowEntryRepository,
                                           MeterRegistry meterRegistry) {
        this.workflowEntryRepository = workflowEntryRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    // Evict-after-invoke (default) is deliberate: eviction runs before commit; a concurrent
    // reader may re-cache the pre-commit value for <=60s (TTL backstop). Do NOT switch to
    // beforeInvocation=true - on rollback the cache would be cold for a value that still exists.
    @CacheEvict(cacheNames = "workflow-entries", key = "#activityId")
    public void onActivityCreated(UUID activityId, String activityName) {
        if (workflowEntryRepository.findByActivityId(activityId).isPresent()) {
            return; // duplicate delivery - already processed
        }
        workflowEntryRepository.save(WorkflowEntry.forActivity(activityId, activityName));
        countCreated();
    }

    @Transactional
    // Evict-after-invoke (default) is deliberate: eviction runs before commit; a concurrent
    // reader may re-cache the pre-commit value for <=60s (TTL backstop). Do NOT switch to
    // beforeInvocation=true - on rollback the cache would be cold for a value that still exists.
    @CacheEvict(cacheNames = "workflow-entries", key = "#activityId")
    public void onActivityUpdated(UUID activityId, String activityName) {
        WorkflowEntry entry = workflowEntryRepository.findByActivityId(activityId).orElse(null);
        if (entry == null) {
            // Update arrived before the create event (out-of-order delivery):
            // reconstruct the entry instead of dropping the update.
            entry = workflowEntryRepository.save(WorkflowEntry.forActivity(activityId, activityName));
            countCreated();
        }
        entry.syncFromActivity(activityName);
        workflowEntryRepository.save(entry);
    }

    @Transactional
    // Evict-after-invoke (default) is deliberate: eviction runs before commit; a concurrent
    // reader may re-cache the pre-commit value for <=60s (TTL backstop). Do NOT switch to
    // beforeInvocation=true - on rollback the cache would be cold for a value that still exists.
    @CacheEvict(cacheNames = "workflow-entries", key = "#activityId")
    public void onActivityDeleted(UUID activityId) {
        workflowEntryRepository.findByActivityId(activityId).ifPresent(workflowEntryRepository::delete);
    }

    /** Counts a workflow row actually persisted, after this listener's transaction commits. */
    private void countCreated() {
        // No reserved suffix (e.g. .created): the Prometheus client strips it, changing the exported name.
        AfterCommitMetrics.incrementAfterCommit(meterRegistry.counter("app.workflow.entries"));
    }
}
