package com.example.app.activity.domain.exception;

/** Raised when an activity operation violates a domain invariant. */
public class InvalidActivityException extends RuntimeException {

    public InvalidActivityException(String message) {
        super(message);
    }
}
