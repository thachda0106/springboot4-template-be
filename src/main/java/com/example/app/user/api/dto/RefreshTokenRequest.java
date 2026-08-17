package com.example.app.user.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Refresh-token rotation/logout payload. */
public record RefreshTokenRequest(

        @NotBlank(message = "refreshToken is required")
        String refreshToken) {
}