package com.example.app.user.infrastructure.persistence;

import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;

/**
 * Maps between the persistence model and the domain model.
 * The only place in the user module that knows both representations.
 */
final class UserEntityMapper {

    private UserEntityMapper() {
    }

    static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(
                user.id().value(),
                user.name(),
                user.email(),
                user.status(),
                user.passwordHash(),
                user.role(),
                user.createdAt(),
                user.updatedAt());
    }

    static User toDomain(UserJpaEntity entity) {
        return User.restore(
                UserId.from(entity.getId()),
                entity.getName(),
                entity.getEmail(),
                entity.getStatus(),
                entity.getPasswordHash(),
                entity.getRole(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
