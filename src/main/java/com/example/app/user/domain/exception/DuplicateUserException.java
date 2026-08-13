package com.example.app.user.domain.exception;

/** Raised when a user with the same email already exists. */
public class DuplicateUserException extends RuntimeException {

    public DuplicateUserException(String email) {
        super("A user with email " + email + " already exists");
    }
}
