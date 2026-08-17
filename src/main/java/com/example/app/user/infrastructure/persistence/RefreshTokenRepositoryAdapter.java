package com.example.app.user.infrastructure.persistence;

import com.example.app.user.domain.model.RefreshToken;
import com.example.app.user.domain.repository.RefreshTokenRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Infrastructure implementation of the refresh-token domain repository contract.
 * The only class in the user module allowed to touch the refresh-token Spring Data repo.
 */
@Repository
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final SpringDataRefreshTokenRepository springDataRepository;

    public RefreshTokenRepositoryAdapter(SpringDataRefreshTokenRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        return RefreshTokenEntityMapper.toDomain(
                springDataRepository.saveAndFlush(RefreshTokenEntityMapper.toEntity(token)));
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return springDataRepository.findByTokenHash(tokenHash).map(RefreshTokenEntityMapper::toDomain);
    }

    @Override
    public boolean consumeIfValid(String tokenHash, Instant now) {
        return springDataRepository.consumeIfValid(tokenHash, now) > 0;
    }
}
