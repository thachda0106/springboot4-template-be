package com.example.app.shared;

/**
 * Raised when a request conflicts with the current state of a resource,
 * e.g. an update carrying a stale optimistic-lock version.
 * Mapped to HTTP 409 CONFLICT by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
