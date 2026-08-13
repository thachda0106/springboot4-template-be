package com.example.app.activity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Update payload. The {@code version} field is the optimistic-lock token the
 * client received in the previous read: sending a stale version yields HTTP 409.
 */
public record UpdateActivityRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must not exceed 200 characters")
        String name,

        @Size(max = 2000, message = "description must not exceed 2000 characters")
        String description,

        @NotNull(message = "version is required for optimistic locking")
        Long version) {
}
