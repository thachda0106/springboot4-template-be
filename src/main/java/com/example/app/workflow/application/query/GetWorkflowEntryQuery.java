package com.example.app.workflow.application.query;

import com.example.app.workflow.domain.exception.WorkflowEntryNotFoundException;
import com.example.app.workflow.domain.model.WorkflowEntry;
import com.example.app.workflow.domain.repository.WorkflowEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Read path for workflow entries, exposed through the module's own API. */
@Service
public class GetWorkflowEntryQuery {

    private final WorkflowEntryRepository workflowEntryRepository;

    public GetWorkflowEntryQuery(WorkflowEntryRepository workflowEntryRepository) {
        this.workflowEntryRepository = workflowEntryRepository;
    }

    @Transactional(readOnly = true)
    public WorkflowEntry findByActivityId(UUID activityId) {
        return workflowEntryRepository.findByActivityId(activityId)
                .orElseThrow(() -> new WorkflowEntryNotFoundException(activityId));
    }
}
