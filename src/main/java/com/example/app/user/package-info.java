/**
 * User bounded context, implemented as a Spring Modulith application module.
 *
 * <p>This module models application users, their profile data, and first-party
 * authentication: credentials (BCrypt password hashes), roles (RBAC), and refresh-token
 * sessions live here. The module issues access tokens through the security module's
 * {@code JwtTokenService} (see docs/security.md).
 *
 * <p>The module root package is its public API: {@link com.example.app.user.UserLookup}
 * is the contract other modules may use for synchronous lookups. Internals are
 * in the api/application/domain/infrastructure sub-packages.
 */
@ApplicationModule(allowedDependencies = {"security", "security::jwt", "shared"})
package com.example.app.user;

import org.springframework.modulith.ApplicationModule;
