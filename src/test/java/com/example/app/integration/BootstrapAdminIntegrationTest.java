package com.example.app.integration;

import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserRole;
import com.example.app.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the first-admin bootstrap: with {@code app.bootstrap.admin-email/password} set,
 * {@code FirstAdminBootstrap} creates the ADMIN user on startup (idempotent).
 */
@SpringBootTest(properties = {
        "app.bootstrap.admin-email=admin@example.com",
        "app.bootstrap.admin-password=admin-pass"
})
@ActiveProfiles("test")
class BootstrapAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void firstAdminIsBootstrappedOnStartup() {
        Optional<User> admin = userRepository.findByEmail("admin@example.com");
        assertThat(admin).isPresent();
        assertThat(admin.get().role()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.get().passwordHash()).isNotBlank();
    }
}
