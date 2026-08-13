package com.example.app.user.domain.model;

import java.util.UUID;

/** Value object identifying a user. */
public record UserId(UUID value) {

    public static UserId random() {
        return new UserId(UUID.randomUUID());
    }

    public static UserId from(UUID value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
