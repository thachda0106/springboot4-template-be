package com.example.app.user.domain.exception;

/**
 * Raised when login credentials are invalid: unknown email, wrong password, inactive
 * account, or a legacy account without a password. Deliberately carries no detail about
 * which case occurred, so callers cannot enumerate accounts.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
