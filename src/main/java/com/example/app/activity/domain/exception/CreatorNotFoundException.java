package com.example.app.activity.domain.exception;

/** Raised when an activity references a creator user that does not exist. */
public class CreatorNotFoundException extends RuntimeException {

    public CreatorNotFoundException(String userId) {
        super("Creator user was not found: " + userId);
    }
}
