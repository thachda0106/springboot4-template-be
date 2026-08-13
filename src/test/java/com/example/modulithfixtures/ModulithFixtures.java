package com.example.modulithfixtures;

import org.springframework.modulith.Modulithic;

/**
 * Marker type anchoring Spring Modulith module detection on the
 * {@code com.example.modulithfixtures} base package (the root type must carry
 * a modulith marker annotation in Modulith 2.1). The actual fixture modules
 * live in the {@code alpha} and {@code beta} sub-packages (see
 * ModuleViolationDetectionTests).
 */
@Modulithic
public final class ModulithFixtures {

    private ModulithFixtures() {
    }
}
