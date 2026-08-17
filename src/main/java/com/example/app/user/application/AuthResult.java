package com.example.app.user.application;

/**
 * Result of a successful login or refresh: the access token, the raw refresh token
 * (returned to the client exactly once) and the access-token lifetime in seconds.
 */
public record AuthResult(String accessToken, String refreshToken, long expiresInSeconds) {
}
