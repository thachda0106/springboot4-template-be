package com.example.app.activity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-boundary validation only (see the difference between API validation and
 * domain invariants in docs/architecture.md). The domain applies its own checks
 * in {@code Activity.create}/{@code update}.
 */
public record CreateActivityRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must not exceed 200 characters")
        String name,

        @Size(max = 2000, message = "description must not exceed 2000 characters")
        String description) {
}
