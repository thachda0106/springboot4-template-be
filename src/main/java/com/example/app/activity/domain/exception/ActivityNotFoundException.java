package com.example.app.activity.domain.exception;

import java.util.UUID;

/** Raised when the requested activity does not exist. */
public class ActivityNotFoundException extends RuntimeException {

    public ActivityNotFoundException(UUID activityId) {
        super("Activity was not found: " + activityId);
    }
}
