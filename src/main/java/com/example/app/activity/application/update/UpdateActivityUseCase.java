package com.example.app.activity.application.update;

import com.example.app.activity.domain.event.ActivityUpdated;
import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.shared.ConflictException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates an activity.
 *
 * <p>Optimistic locking, two layers:
 * <ol>
 *   <li>explicit check: the client must send the version it read; a stale version
 *       fails fast with {@link ConflictException} (HTTP 409);</li>
 *   <li>JPA {@code @Version}: closes the check-then-write race at the database level.</li>
 * </ol>
 */
@Service
public class UpdateActivityUseCase {

    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateActivityUseCase(ActivityRepository activityRepository, ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Activity execute(ActivityId id, String name, String description, Long expectedVersion) {
        Activity activity = activityRepository.findById(id)
                .orElseThrow(() -> new ActivityNotFoundException(id.value()));

        if (expectedVersion == null || !expectedVersion.equals(activity.version())) {
            throw new ConflictException("Stale version for activity " + id
                    + ": expected " + expectedVersion + " but was " + activity.version());
        }

        activity.update(name, description);
        Activity saved = activityRepository.save(activity);

        eventPublisher.publishEvent(new ActivityUpdated(saved.id().value(), saved.name(), saved.status().name()));
        return saved;
    }
}
