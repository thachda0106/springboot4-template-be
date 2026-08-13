package com.example.app.activity.domain.event;

import java.util.UUID;

/**
 * Published when an activity is created.
 * Carries only primitive values so consumers never depend on activity internals.
 */
public record ActivityCreated(UUID activityId, String name) {
}
