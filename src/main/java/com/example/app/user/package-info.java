/**
 * User bounded context, implemented as a Spring Modulith application module.
 *
 * <p>This module is a <em>business</em> module - it models application users and
 * their profile data. It is NOT an Identity Provider: authentication is delegated
 * to an external OAuth2/OIDC provider, and no credentials live in this module's
 * domain (see docs/security.md).
 *
 * <p>The module root package is its public API: {@link com.example.app.user.UserLookup}
 * is the contract other modules may use for synchronous lookups. Internals are
 * in the api/application/domain/infrastructure sub-packages.
 */
@ApplicationModule(allowedDependencies = {"security", "shared"})
package com.example.app.user;

import org.springframework.modulith.ApplicationModule;
