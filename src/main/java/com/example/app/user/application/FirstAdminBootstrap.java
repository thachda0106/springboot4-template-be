package com.example.app.user.application;

import com.example.app.user.application.port.PasswordHasher;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserRole;
import com.example.app.user.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps the first ADMIN user on startup when {@code app.bootstrap.admin-email} and
 * {@code app.bootstrap.admin-password} are configured and no user with that email exists
 * yet. Idempotent: once the email is taken (any role), this does nothing. Provides the
 * provisioning path for a fresh deployment (see docs/security.md).
 */
@Component
public class FirstAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final String adminEmail;
    private final String adminPassword;

    public FirstAdminBootstrap(UserRepository userRepository, PasswordHasher passwordHasher,
                               @Value("${app.bootstrap.admin-email:}") String adminEmail,
                               @Value("${app.bootstrap.admin-password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            return;
        }
        String normalizedEmail = adminEmail.trim().toLowerCase();
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            log.warn("Bootstrap admin email {} already exists; skipping", normalizedEmail);
            return;
        }
        userRepository.save(User.create("Administrator", normalizedEmail,
                passwordHasher.hash(adminPassword), UserRole.ADMIN));
        log.info("Bootstrapped first ADMIN user {}", normalizedEmail);
    }
}