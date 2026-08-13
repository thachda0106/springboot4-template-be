/**
 * Activity bounded context, implemented as a Spring Modulith application module.
 *
 * <p>Explicit dependency whitelist: other modules' internals are never accessible.
 * <ul>
 *   <li>{@code user} - public API only (UserLookup), used to resolve the creator</li>
 *   <li>{@code security} - CurrentUser / CurrentUserProvider abstractions</li>
 *   <li>{@code shared} - the API error contract</li>
 * </ul>
 * The activity module publishes lifecycle events (ActivityCreated/Updated/Deleted)
 * through the named interface {@code events}; it never depends on the workflow module.
 */
@ApplicationModule(allowedDependencies = {"user", "security", "shared"})
package com.example.app.activity;

import org.springframework.modulith.ApplicationModule;
