package com.example.app.user.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-boundary validation for user creation. No credentials are accepted:
 * authentication is externalized to the Identity Provider.
 */
public record CreateUserRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must not exceed 200 characters")
        String name,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must not exceed 320 characters")
        String email) {
}
