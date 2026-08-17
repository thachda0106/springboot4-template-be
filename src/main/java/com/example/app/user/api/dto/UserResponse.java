package com.example.app.user.api.dto;

import com.example.app.user.domain.model.User;

import java.time.Instant;

/** REST representation of a user. JPA entities are never exposed directly. */
public record UserResponse(
        String id,
        String name,
        String email,
        String status,
        String role,
        Instant createdAt,
        Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.id().value().toString(),
                user.name(),
                user.email(),
                user.status().name(),
                user.role().name(),
                user.createdAt(),
                user.updatedAt());
    }
}