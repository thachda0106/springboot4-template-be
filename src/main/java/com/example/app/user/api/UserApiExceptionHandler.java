package com.example.app.user.api;

import com.example.app.shared.ApiError;
import com.example.app.user.domain.exception.DuplicateUserException;
import com.example.app.user.domain.exception.InvalidCredentialsException;
import com.example.app.user.domain.exception.InvalidRefreshTokenException;
import com.example.app.user.domain.exception.InvalidUserException;
import com.example.app.user.domain.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the user module's business exceptions (and the unique-email race at the
 * database level) to the consistent {@link ApiError} contract.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}: Spring's exception resolution consults
 * advice beans in order and takes the first match; the shared
 * {@code GlobalExceptionHandler} (with its {@code Exception} catch-all) must be
 * the last resort, never the first match.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserApiExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return ApiError.of("USER_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(DuplicateUserException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError handleDuplicate(DuplicateUserException ex, HttpServletRequest request) {
        return ApiError.of("USER_ALREADY_EXISTS", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidUserException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleInvalid(InvalidUserException ex, HttpServletRequest request) {
        return ApiError.of("INVALID_USER", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return ApiError.of("USER_ALREADY_EXISTS", "A user with this email already exists", request.getRequestURI());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiError handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return ApiError.of("INVALID_CREDENTIALS", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiError handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return ApiError.of("INVALID_REFRESH_TOKEN", ex.getMessage(), request.getRequestURI());
    }
}
