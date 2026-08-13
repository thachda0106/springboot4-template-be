package com.example.app.workflow.domain.exception;

import java.util.UUID;

/** Raised when no workflow entry exists for the requested activity. */
public class WorkflowEntryNotFoundException extends RuntimeException {

    public WorkflowEntryNotFoundException(UUID activityId) {
        super("Workflow entry was not found for activity: " + activityId);
    }
}
