package com.example.app.architecture;

import com.example.modulithfixtures.ModulithFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that Spring Modulith actually REJECTS invalid dependencies.
 *
 * <p>The {@code com.example.modulithfixtures} test package contains a deliberate
 * violation: the {@code beta} module references a class from {@code alpha}'s
 * internal sub-package. {@code ApplicationModules.verify()} must fail on it -
 * this is the same check that protects the real application modules.
 */
class ModuleViolationDetectionTests {

    @Test
    void accessToAnotherModulesInternalPackageIsDetected() {
        // Anchor detection on the marker class in the fixture base package, and
        // include test classpath locations (Modulith excludes them by default).
        ApplicationModules modules = ApplicationModules.of(ModulithFixtures.class, location -> true);

        assertThatThrownBy(modules::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("non-exposed type")
                .hasMessageContaining("alpha");
    }
}
