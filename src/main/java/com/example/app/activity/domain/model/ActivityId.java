package com.example.app.activity.domain.model;

import java.util.UUID;

/** Value object identifying an activity. */
public record ActivityId(UUID value) {

    public static ActivityId random() {
        return new ActivityId(UUID.randomUUID());
    }

    public static ActivityId from(UUID value) {
        return new ActivityId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
