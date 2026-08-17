package com.example.app.user.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login credentials. The byte-level BCrypt limit is enforced in the use case. */
public record LoginRequest(

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must not exceed 320 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(max = 72, message = "password must not exceed 72 characters")
        String password) {
}