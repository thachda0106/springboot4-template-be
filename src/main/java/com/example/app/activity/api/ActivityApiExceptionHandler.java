package com.example.app.activity.api;

import com.example.app.activity.domain.exception.ActivityNotFoundException;
import com.example.app.activity.domain.exception.CreatorNotFoundException;
import com.example.app.activity.domain.exception.InvalidActivityException;
import com.example.app.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the activity module's own business exceptions to the consistent
 * {@link ApiError} contract. Technical errors are handled by the shared advice.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}: must be consulted before the shared
 * {@code GlobalExceptionHandler}'s {@code Exception} catch-all (see the javadoc
 * of UserApiExceptionHandler for the resolution semantics).
 */
@RestControllerAdvice(assignableTypes = ActivityController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActivityApiExceptionHandler {

    @ExceptionHandler(ActivityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleNotFound(ActivityNotFoundException ex, HttpServletRequest request) {
        return ApiError.of("ACTIVITY_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CreatorNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleCreatorNotFound(CreatorNotFoundException ex, HttpServletRequest request) {
        return ApiError.of("CREATOR_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidActivityException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleInvalid(InvalidActivityException ex, HttpServletRequest request) {
        return ApiError.of("INVALID_ACTIVITY", ex.getMessage(), request.getRequestURI());
    }
}
