package com.example.app.activity.infrastructure.persistence;

import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;
import com.example.app.activity.domain.repository.ActivityRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Infrastructure implementation of the domain repository contract.
 * This is the only class in the activity module allowed to touch Spring Data.
 */
@Repository
public class ActivityRepositoryAdapter implements ActivityRepository {

    private final SpringDataActivityRepository springDataRepository;

    public ActivityRepositoryAdapter(SpringDataActivityRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public Activity save(Activity activity) {
        // saveAndFlush: with assigned UUIDs the INSERT is otherwise deferred to
        // commit, leaving @CreationTimestamp/@Version unpopulated on the returned
        // aggregate (the response would lack timestamps and version).
        ActivityJpaEntity saved = springDataRepository.saveAndFlush(ActivityEntityMapper.toEntity(activity));
        return ActivityEntityMapper.toDomain(saved);
    }

    @Override
    public Optional<Activity> findById(ActivityId id) {
        return springDataRepository.findById(id.value()).map(ActivityEntityMapper::toDomain);
    }

    @Override
    public void delete(ActivityId id) {
        springDataRepository.deleteById(id.value());
    }
}
