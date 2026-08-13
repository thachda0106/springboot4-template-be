package com.example.app.activity.application.delete;

import com.example.app.activity.domain.event.ActivityDeleted;
import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes an activity and publishes {@code ActivityDeleted} in the same transaction.
 */
@Service
public class DeleteActivityUseCase {

    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeleteActivityUseCase(ActivityRepository activityRepository, ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void execute(ActivityId id) {
        Activity activity = activityRepository.findById(id)
                .orElseThrow(() -> new ActivityNotFoundException(id.value()));

        activityRepository.delete(activity.id());
        eventPublisher.publishEvent(new ActivityDeleted(id.value()));
    }
}
