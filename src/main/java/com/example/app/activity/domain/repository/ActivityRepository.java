package com.example.app.activity.domain.repository;

import com.example.app.activity.domain.model.Activity;
import com.example.app.activity.domain.model.ActivityId;

import java.util.Optional;

/**
 * Domain repository contract for activities.
 *
 * <p>Declared in the domain layer, implemented in the infrastructure layer
 * ({@code ActivityRepositoryAdapter}). Application and domain code depend only on
 * this interface - never on Spring Data or JPA types.
 */
public interface ActivityRepository {

    Activity save(Activity activity);

    Optional<Activity> findById(ActivityId id);

    void delete(ActivityId id);
}
