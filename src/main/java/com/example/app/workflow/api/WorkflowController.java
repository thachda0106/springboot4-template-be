package com.example.app.workflow.api;

import com.example.app.workflow.api.dto.WorkflowEntryResponse;
import com.example.app.workflow.application.query.GetWorkflowEntryQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST API of the workflow module: exposes the workflow view of an activity.
 * All writes to this module happen through events, never through this API.
 */
@RestController
@RequestMapping("/workflow-entries")
public class WorkflowController {

    private final GetWorkflowEntryQuery getWorkflowEntryQuery;

    public WorkflowController(GetWorkflowEntryQuery getWorkflowEntryQuery) {
        this.getWorkflowEntryQuery = getWorkflowEntryQuery;
    }

    @GetMapping("/{activityId}")
    public WorkflowEntryResponse get(@PathVariable UUID activityId) {
        return WorkflowEntryResponse.from(getWorkflowEntryQuery.findByActivityId(activityId));
    }
}
