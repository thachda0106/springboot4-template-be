/**
 * Cross-cutting technical concerns only: the consistent API error contract,
 * technical exception handling, web/OpenAPI configuration, Redis cache
 * infrastructure and after-commit metrics. No business types, entities, DTOs
 * or module-specific exceptions live here.
 *
 * <p>The root package is the module's public API: {@link com.example.app.shared.ApiError},
 * {@link com.example.app.shared.ConflictException}, {@link com.example.app.shared.RedisCacheConfigurer}
 * and {@link com.example.app.shared.AfterCommitMetrics} are the contracts other modules may use.
 * Internals are grouped by responsibility: {@code error/} (exception handling),
 * {@code web/} (path prefix, OpenAPI docs) and {@code cache/} (Redis cache infrastructure).
 */
package com.example.app.shared;
