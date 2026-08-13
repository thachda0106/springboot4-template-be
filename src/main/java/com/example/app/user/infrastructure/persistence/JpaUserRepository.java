package com.example.app.user.infrastructure.persistence;

import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;
import com.example.app.user.domain.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Infrastructure implementation of the user domain repository contract.
 * The only class in the user module allowed to touch Spring Data.
 */
@Repository
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public JpaUserRepository(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public User save(User user) {
        // saveAndFlush: see JpaActivityRepository for the rationale.
        return UserEntityMapper.toDomain(jpaRepository.saveAndFlush(UserEntityMapper.toEntity(user)));
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jpaRepository.findById(id.value()).map(UserEntityMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(UserEntityMapper::toDomain);
    }
}
