package com.example.app.user;

import java.util.Optional;

/**
 * Public API of the user bounded context, exposed through the module root
 * package. Other modules (e.g. activity) may use this interface for synchronous
 * lookups; they must never touch the user module's internal classes.
 */
public interface UserLookup {

    Optional<Summary> findById(String userId);

    /** Read-only projection of a user, safe to share across module boundaries. */
    record Summary(String id, String name) {
    }
}
