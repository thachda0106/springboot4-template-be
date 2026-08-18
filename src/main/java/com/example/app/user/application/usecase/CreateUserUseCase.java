package com.example.app.user.application.usecase;

import com.example.app.user.application.policy.PasswordRules;
import com.example.app.user.application.port.PasswordHasher;
import com.example.app.user.domain.exception.DuplicateUserException;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserRole;
import com.example.app.user.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a user with a password and role. The duplicate-email check is application-level;
 * the unique constraint in the database is the final guarantee (a lost race surfaces as
 * {@code DataIntegrityViolationException}, mapped to 409 by the module's advice).
 */
@Service
public class CreateUserUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public CreateUserUseCase(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public User execute(String name, String email, String rawPassword, UserRole role) {
        PasswordRules.requireWithinBcryptLimit(rawPassword);
        String normalizedEmail = email.trim().toLowerCase();
        userRepository.findByEmail(normalizedEmail).ifPresent(existing -> {
            throw new DuplicateUserException(normalizedEmail);
        });
        UserRole effectiveRole = role != null ? role : UserRole.USER;
        String passwordHash = passwordHasher.hash(rawPassword);
        return userRepository.save(User.create(name, normalizedEmail, passwordHash, effectiveRole));
    }
}