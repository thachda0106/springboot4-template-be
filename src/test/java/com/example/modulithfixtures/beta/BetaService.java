package com.example.modulithfixtures.beta;

import com.example.modulithfixtures.alpha.internal.AlphaInternal;

/**
 * Module "beta" - DELIBERATE VIOLATION: it references alpha's internal package.
 * This fixture exists only to prove that Spring Modulith's verification rejects
 * such dependencies (see ModuleViolationDetectionTests).
 */
public class BetaService {

    private final AlphaInternal alphaInternal = new AlphaInternal();
}
