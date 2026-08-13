package com.example.app.security;

/**
 * Provides the currently authenticated user at the application boundary.
 * Implementations read the Spring Security context; callers never do.
 */
public interface CurrentUserProvider {

    CurrentUser currentUser();
}
