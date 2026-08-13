package com.example.app.workflow.api;

import com.example.app.shared.ApiError;
import com.example.app.workflow.domain.exception.WorkflowEntryNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the workflow module's business exceptions to the {@link ApiError} contract.
 * {@code @Order(HIGHEST_PRECEDENCE)}: see UserApiExceptionHandler javadoc.
 */
@RestControllerAdvice(assignableTypes = WorkflowController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkflowApiExceptionHandler {

    @ExceptionHandler(WorkflowEntryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleNotFound(WorkflowEntryNotFoundException ex, HttpServletRequest request) {
        return ApiError.of("WORKFLOW_ENTRY_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }
}
