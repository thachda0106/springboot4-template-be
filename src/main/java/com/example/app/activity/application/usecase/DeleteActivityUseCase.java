package com.example.app.activity.application.usecase;

import com.example.app.activity.domain.event.ActivityDeleted;
import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.shared.AfterCommitMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.annotation.CacheEvict;
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
    private final MeterRegistry meterRegistry;

    public DeleteActivityUseCase(ActivityRepository activityRepository, ApplicationEventPublisher eventPublisher,
                                 MeterRegistry meterRegistry) {
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    // Evict-after-invoke (default) is deliberate: eviction runs before commit; a concurrent
    // reader may re-cache the pre-commit value for <=60s (TTL backstop). Do NOT switch to
    // beforeInvocation=true - on rollback the cache would be cold for a value that still exists.
    @CacheEvict(cacheNames = "activities", key = "#id")
    public void execute(ActivityId id) {
        Activity activity = activityRepository.findById(id)
                .orElseThrow(() -> new ActivityNotFoundException(id.value()));

        activityRepository.delete(activity.id());
        eventPublisher.publishEvent(new ActivityDeleted(id.value()));
        AfterCommitMetrics.incrementAfterCommit(
                meterRegistry.counter("app.activities.lifecycle", "action", "deleted"));
    }
}
