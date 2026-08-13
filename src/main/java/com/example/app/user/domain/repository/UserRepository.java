package com.example.app.user.domain.repository;

import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;

import java.util.Optional;

/**
 * Domain repository contract for users.
 * Implemented in the infrastructure layer; application/domain code never sees JPA.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UserId id);

    Optional<User> findByEmail(String email);
}
