package com.example.app.shared;

import java.time.Instant;
import java.util.List;

/**
 * Consistent REST error payload returned by every error path:
 * validation failures, business errors, 401/403 responses and unexpected
 * errors.
 *
 * <p>
 * This is the only cross-cutting type in the {@code shared} module: it carries
 * no business state and is used by all modules' exception handlers and by the
 * security layer. Module-specific exceptions stay inside their own module.
 */
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String path,
        List<FieldError> fieldErrors) {

    /** A single field-level validation failure. */
    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message, String path) {
        return new ApiError(code, message, Instant.now(), path, null);
    }

    public static ApiError of(String code, String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(code, message, Instant.now(), path, fieldErrors);
    }
}
