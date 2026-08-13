package com.example.app.user.domain.exception;

/** Raised when a user operation violates a domain invariant. */
public class InvalidUserException extends RuntimeException {

    public InvalidUserException(String message) {
        super(message);
    }
}
