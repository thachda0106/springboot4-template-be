/**
 * Security bounded context: JWT validation and issuance, the filter chain, and the
 * {@code CurrentUser} abstraction. This module is a leaf — it depends only on {@code shared}.
 *
 * <p>The root package is the module's public API: {@link com.example.app.security.JwtTokenService},
 * {@link com.example.app.security.PasswordEncoderConfig}, {@link com.example.app.security.CurrentUserProvider}
 * and {@link com.example.app.security.CurrentUser} are the contracts other modules may use.
 * Decoder and configuration classes are internal implementation details.
 */
@ApplicationModule(allowedDependencies = "shared")
package com.example.app.security;

import org.springframework.modulith.ApplicationModule;
