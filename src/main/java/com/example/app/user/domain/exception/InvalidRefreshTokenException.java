package com.example.app.user.domain.exception;

/** Raised when a refresh token is unknown, revoked or expired. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}
