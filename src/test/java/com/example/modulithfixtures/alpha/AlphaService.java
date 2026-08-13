package com.example.modulithfixtures.alpha;

/**
 * Module "alpha" - deliberately a fixture outside the application base package
 * so the main architecture verification is not polluted by it.
 */
public class AlphaService {

    public String ping() {
        return new com.example.modulithfixtures.alpha.internal.AlphaInternal().secret();
    }
}
