package com.example.app.workflow.application.listener;

import com.example.app.activity.domain.event.ActivityCreated;
import com.example.app.activity.domain.event.ActivityDeleted;
import com.example.app.activity.domain.event.ActivityUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Entry points of the workflow module for the activity module's events.
 *
 * <p>{@code @ApplicationModuleListener} (Spring Modulith 2.1.0) is composed of
 * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Transactional(REQUIRES_NEW)}
 * + {@code @Async} (inert without {@code @EnableAsync}, so execution is synchronous
 * in the publishing thread). Verified semantics - see docs/event-driven.md.
 */
@Component
public class WorkflowEventListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final WorkflowEntryApplicationService workflowEntryApplicationService;

    public WorkflowEventListener(WorkflowEntryApplicationService workflowEntryApplicationService) {
        this.workflowEntryApplicationService = workflowEntryApplicationService;
    }

    @ApplicationModuleListener
    public void onActivityCreated(ActivityCreated event) {
        log.debug("Workflow reacting to ActivityCreated {}", event.activityId());
        workflowEntryApplicationService.onActivityCreated(event.activityId(), event.name());
    }

    @ApplicationModuleListener
    public void onActivityUpdated(ActivityUpdated event) {
        log.debug("Workflow reacting to ActivityUpdated {}", event.activityId());
        workflowEntryApplicationService.onActivityUpdated(event.activityId(), event.name());
    }

    @ApplicationModuleListener
    public void onActivityDeleted(ActivityDeleted event) {
        log.debug("Workflow reacting to ActivityDeleted {}", event.activityId());
        workflowEntryApplicationService.onActivityDeleted(event.activityId());
    }
}
