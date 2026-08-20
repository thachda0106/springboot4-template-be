package com.example.app.activity.application.query;

import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read path for a single activity. Kept separate from the command use cases so
 * reads and writes can evolve independently (and later be scaled separately).
 *
 * <p>Cached in Redis ({@code activities:<id>}, 60s TTL backstop). All writes go
 * through the use cases, which evict this cache — see {@code Create/Update/Delete
 * ActivityUseCase}. On a Redis outage the cache fails open (miss → DB read).
 */
@Service
public class GetActivityQuery {

    private final ActivityRepository activityRepository;

    public GetActivityQuery(ActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "activities", key = "#id")
    public Activity findById(ActivityId id) {
        return activityRepository.findById(id)
                .orElseThrow(() -> new ActivityNotFoundException(id.value()));
    }
}
