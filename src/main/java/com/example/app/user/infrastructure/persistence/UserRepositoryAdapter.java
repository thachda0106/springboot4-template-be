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
public class UserRepositoryAdapter implements UserRepository {

    private final SpringDataUserRepository springDataRepository;

    public UserRepositoryAdapter(SpringDataUserRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public User save(User user) {
        // saveAndFlush: see ActivityRepositoryAdapter for the rationale.
        return UserEntityMapper.toDomain(springDataRepository.saveAndFlush(UserEntityMapper.toEntity(user)));
    }

    @Override
    public Optional<User> findById(UserId id) {
        return springDataRepository.findById(id.value()).map(UserEntityMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return springDataRepository.findByEmail(email).map(UserEntityMapper::toDomain);
    }
}
