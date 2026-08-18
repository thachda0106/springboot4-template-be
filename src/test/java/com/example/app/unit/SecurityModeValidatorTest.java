package com.example.app.unit;

import com.example.app.security.jwt.SecurityModeValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the mutually-exclusive JWT key-mode validation.
 */
class SecurityModeValidatorTest {

    @Test
    void acceptsHmacOnly() {
        assertThatCode(() -> new SecurityModeValidator("secret", "", "")).doesNotThrowAnyException();
    }

    @Test
    void acceptsRsaOnly() {
        assertThatCode(() -> new SecurityModeValidator("", "priv", "pub")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBothModes() {
        assertThatThrownBy(() -> new SecurityModeValidator("secret", "priv", "pub"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNeitherMode() {
        assertThatThrownBy(() -> new SecurityModeValidator("", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPartialRsaPair() {
        assertThatThrownBy(() -> new SecurityModeValidator("", "priv", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
