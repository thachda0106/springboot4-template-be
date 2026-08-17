package com.example.app.user.api.dto;

import com.example.app.user.application.AuthResult;

/** Token pair returned by login and refresh. */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {

    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(result.accessToken(), result.refreshToken(), "Bearer", result.expiresInSeconds());
    }
}