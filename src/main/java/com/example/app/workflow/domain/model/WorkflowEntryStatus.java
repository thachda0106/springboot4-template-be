package com.example.app.workflow.domain.model;

/** State of a workflow entry as it tracks an activity through its lifecycle. */
public enum WorkflowEntryStatus {
    /** The activity was created; the workflow entry was created in reaction. */
    CREATED,
    /** The activity was updated; the workflow entry was synchronized. */
    UPDATED
}
