package com.example.app.workflow.application.query;

import com.example.app.workflow.domain.exception.WorkflowEntryNotFoundException;
import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.repository.WorkflowEntryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read path for workflow entries, exposed through the module's own API.
 *
 * <p>Cached in Redis ({@code workflow-entries:<activityId>}, 60s TTL backstop).
 * All writes happen through the event listener
 * ({@code WorkflowEntryApplicationService}), which evicts this cache. On a Redis
 * outage the cache fails open (miss → DB read).
 */
@Service
public class GetWorkflowEntryQuery {

    private final WorkflowEntryRepository workflowEntryRepository;

    public GetWorkflowEntryQuery(WorkflowEntryRepository workflowEntryRepository) {
        this.workflowEntryRepository = workflowEntryRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "workflow-entries", key = "#activityId")
    public WorkflowEntry findByActivityId(UUID activityId) {
        return workflowEntryRepository.findByActivityId(activityId)
                .orElseThrow(() -> new WorkflowEntryNotFoundException(activityId));
    }
}
