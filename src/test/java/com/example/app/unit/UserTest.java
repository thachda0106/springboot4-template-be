package com.example.app.unit;

import com.example.app.user.domain.exception.InvalidUserException;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;
import com.example.app.user.domain.model.UserRole;
import com.example.app.user.domain.model.UserStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure domain unit tests for the user aggregate - no Spring context.
 */
class UserTest {

    @Test
    void createNormalizesEmailAndSetsRole() {
        User user = User.create("  Alice  ", "  Alice@Example.com ", "hash", UserRole.ADMIN);

        assertThat(user.name()).isEqualTo("Alice");
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.passwordHash()).isEqualTo("hash");
        assertThat(user.role()).isEqualTo(UserRole.ADMIN);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.id()).isNotNull();
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> User.create("  ", "a@b.com", "hash", UserRole.USER))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createRejectsBlankEmail() {
        assertThatThrownBy(() -> User.create("Alice", " ", "hash", UserRole.USER))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("email");
    }

    @Test
    void createRejectsBlankPasswordHash() {
        assertThatThrownBy(() -> User.create("Alice", "a@b.com", " ", UserRole.USER))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("password");
    }

    @Test
    void createRejectsNullRole() {
        assertThatThrownBy(() -> User.create("Alice", "a@b.com", "hash", null))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("role");
    }

    @Test
    void restoreAllowsLegacyNullPasswordHash() {
        User user = User.restore(UserId.random(), "Alice", "a@b.com", UserStatus.ACTIVE,
                null, UserRole.USER, null, null);

        assertThat(user.passwordHash()).isNull();
        assertThat(user.role()).isEqualTo(UserRole.USER);
    }
}
