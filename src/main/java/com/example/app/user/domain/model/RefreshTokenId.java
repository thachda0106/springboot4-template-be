package com.example.app.user.domain.model;

import java.util.UUID;

/** Value object identifying a refresh token. */
public record RefreshTokenId(UUID value) {

    public static RefreshTokenId random() {
        return new RefreshTokenId(UUID.randomUUID());
    }

    public static RefreshTokenId from(UUID value) {
        return new RefreshTokenId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
