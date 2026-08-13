package com.example.app.user.application;

import com.example.app.user.domain.exception.DuplicateUserException;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a user. The duplicate-email check is application-level; the unique
 * constraint in the database is the final guarantee (a lost race surfaces as
 * {@code DataIntegrityViolationException}, mapped to 409 by the module's advice).
 */
@Service
public class CreateUserUseCase {

    private final UserRepository userRepository;

    public CreateUserUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User execute(String name, String email) {
        String normalizedEmail = email.trim().toLowerCase();
        userRepository.findByEmail(normalizedEmail).ifPresent(existing -> {
            throw new DuplicateUserException(normalizedEmail);
        });
        return userRepository.save(User.create(name, normalizedEmail));
    }
}
