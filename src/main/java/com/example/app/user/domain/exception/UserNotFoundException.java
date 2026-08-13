package com.example.app.user.domain.exception;

/** Raised when the requested user does not exist. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String userId) {
        super("User was not found: " + userId);
    }
}
