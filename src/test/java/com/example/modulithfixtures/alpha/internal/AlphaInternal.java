package com.example.modulithfixtures.alpha.internal;

/**
 * Internal implementation detail of module "alpha".
 * A class in another module must never reference this type - the
 * ModuleViolationDetectionTests prove the enforcement works.
 */
public class AlphaInternal {

    public String secret() {
        return "secret";
    }
}
