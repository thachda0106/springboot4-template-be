package com.example.app.user.infrastructure.persistence;

import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.model.RefreshTokenId;
import com.example.app.user.domain.model.UserId;

/**
 * Maps between the persistence model and the domain model for refresh tokens.
 * The only place in the user module that knows both representations.
 */
final class RefreshTokenEntityMapper {

    private RefreshTokenEntityMapper() {
    }

    static RefreshTokenJpaEntity toEntity(RefreshToken token) {
        return new RefreshTokenJpaEntity(
                token.id().value(),
                token.userId().value(),
                token.tokenHash(),
                token.expiresAt(),
                token.revokedAt(),
                token.createdAt());
    }

    static RefreshToken toDomain(RefreshTokenJpaEntity entity) {
        return RefreshToken.restore(
                RefreshTokenId.from(entity.getId()),
                UserId.from(entity.getUserId()),
                entity.getTokenHash(),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getCreatedAt());
    }
}
