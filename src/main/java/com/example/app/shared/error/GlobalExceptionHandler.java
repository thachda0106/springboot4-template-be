package com.example.app.shared.error;

import com.example.app.shared.ApiError;
import com.example.app.shared.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Cross-cutting REST error handling: validation failures, malformed requests,
 * unknown paths, optimistic-lock conflicts and unexpected errors.
 *
 * <p>
 * Module-specific business exceptions (e.g. {@code ActivityNotFoundException})
 * are handled by each module's own {@code @RestControllerAdvice}; Spring picks
 * the
 * most specific handler, so this advice is only the fallback for technical
 * errors.
 * No stack traces or internal details are ever exposed to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ApiError.of("VALIDATION_ERROR", "Request validation failed", request.getRequestURI(), fieldErrors);
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleMalformedRequest(Exception ex, HttpServletRequest request) {
        return ApiError.of("MALFORMED_REQUEST", "Request body or path parameter is malformed", request.getRequestURI());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleUnknownPath(NoResourceFoundException ex, HttpServletRequest request) {
        return ApiError.of("NOT_FOUND", "Resource not found", request.getRequestURI());
    }

    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, ConflictException.class })
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError handleConflict(Exception ex, HttpServletRequest request) {
        return ApiError.of("CONFLICT", "The resource was modified concurrently; reload it and retry",
                request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiError handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ApiError.of("INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI());
    }
}
