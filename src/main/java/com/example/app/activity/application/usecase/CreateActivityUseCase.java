package com.example.app.activity.application.usecase;

import com.example.app.activity.domain.event.ActivityCreated;
import com.example.app.activity.domain.exception.CreatorNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.repository.ActivityRepository;
import com.example.app.security.CurrentUser;
import com.example.app.shared.AfterCommitMetrics;
import com.example.app.user.UserLookup;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an activity.
 *
 * <p>Owns the transaction: the insert and the registration of the
 * {@code ActivityCreated} event happen in the same database transaction.
 * Listeners run only after this transaction commits (see docs/transaction-boundaries.md).
 *
 * <p>Uses the user module's public API ({@link UserLookup}) to validate the
 * creator - a synchronous cross-module call through the exposed contract only.
 */
@Service
public class CreateActivityUseCase {

    private final ActivityRepository activityRepository;
    private final UserLookup userLookup;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public CreateActivityUseCase(ActivityRepository activityRepository, UserLookup userLookup,
                                 ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.activityRepository = activityRepository;
        this.userLookup = userLookup;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    // Evict-after-invoke (default) is deliberate: eviction runs before commit; a concurrent
    // reader may re-cache the pre-commit value for <=60s (TTL backstop). Do NOT switch to
    // beforeInvocation=true - on rollback the cache would be cold for a value that still exists.
    @CacheEvict(cacheNames = "activities", key = "#result.id")
    public Activity execute(String name, String description, CurrentUser actor) {
        userLookup.findById(actor.id())
                .orElseThrow(() -> new CreatorNotFoundException(actor.id()));

        Activity activity = Activity.create(name, description, actor.id());
        Activity saved = activityRepository.save(activity);

        eventPublisher.publishEvent(new ActivityCreated(saved.id().value(), saved.name()));
        AfterCommitMetrics.incrementAfterCommit(
                meterRegistry.counter("app.activities.lifecycle", "action", "created"));
        return saved;
    }
}
