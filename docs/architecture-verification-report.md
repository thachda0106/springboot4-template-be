# Architecture Verification Report

**Project:** Spring Boot Modular Monolith Template
**Date:** 2026-08-13
**Status:** ✅ All verification passed on a real build, real tests, real containers

---

## 1. Technology versions (verified against Maven Central / official docs)

| Component | Version | Verified by |
|---|---|---|
| Java | 21 (Temurin 21.0.11) | `mvnw.cmd -v` |
| Spring Boot | 4.1.0 (parent POM, plain semver) | resolved parent POM |
| Spring Framework | 7.0.8 | Boot BOM |
| Spring Security | 7.1.0 | Boot BOM |
| Spring Modulith | 2.1.0 (`spring-modulith-bom`) | Central metadata + `javap` on artifacts |
| Hibernate | 7.4.1.Final | Boot BOM |
| Flyway | 12.11.0 (BOM pins 12.4.0 — overridden, see §19) | dependency tree |
| PostgreSQL driver | 42.7.11 | Boot BOM |
| Micrometer | 1.17.0 + prometheus registry | Boot BOM |
| Testcontainers | 2.0.5 (via Boot BOM's testcontainers-bom import) | dependency tree |
| Tomcat | 11.0.x (embedded) | runtime logs |

## 2. Maven dependencies (final)

Runtime: `webmvc`, `validation`, `data-jpa`, `security`, `oauth2-resource-server`,
`flyway` + `flyway-database-postgresql` (PostgreSQL plugin — required since Flyway 10+),
`actuator`, `micrometer-registry-prometheus`, `postgresql` (runtime), `spring-modulith-starter-core`
(detection/verification), `spring-modulith-events-api` (`@ApplicationModuleListener`).

Test: `webmvc-test`, `validation-test`, `actuator-test`, `spring-security-test`,
`testcontainers-junit-jupiter`.

**Deliberately absent:** `spring-modulith-events-core` (its auto-configs require a durable
publication registry — Stage 2), `spring-boot-testcontainers` (per-DB modules and
`@ServiceConnection` factories were dropped in Testcontainers 2.x; `@DynamicPropertySource`
is used instead), Kafka, any JWT library (nimbus comes with the resource-server starter),
any CQRS/event-sourcing framework. **Present and deliberate:** Redis
(`spring-boot-starter-data-redis` + `spring-boot-starter-cache`) for the distributed rate
limiter and the read cache (§17b below, docs/security.md §8, docs/architecture.md §8a).

## 3. Module dependency graph (as verified by tests)

```text
shared ← security ← user ← activity → (events) → workflow
```

`ApplicationModularityTests` asserts the exact sets: activity = {user, security, shared},
workflow = {activity, shared}, user = {security, shared}, security = {shared}, shared = {}.
All verified with `getDirectDependencies(...).uniqueModules()` against the compiled code.

## 4. Bounded Context mapping

| Bounded Context | Module | Tables |
|---|---|---|
| Activity | `activity` | `activities` |
| Workflow | `workflow` | `workflow_entries` |
| User | `user` | `users` |

Documented distinction (BC = business boundary; Modulith module = technical enforcement):
docs/architecture.md §2.

## 5. Spring Modulith module mapping

`com.example.app.{activity,workflow,user,security,shared}` — 5 modules detected by
`ApplicationModules.of(Application.class)` (asserted in tests). Named interfaces:
`activity::api`, `activity::events`, `workflow::api`, `user::api`.

## 6. Public APIs

| Module | Contract | Consumers |
|---|---|---|
| user | `UserLookup` (root package) | activity |
| activity | `api` named interface (REST), `events` named interface | workflow (events only) |
| workflow | `api` named interface (read-only REST) | HTTP clients |
| security | root package (`CurrentUser`, `CurrentUserProvider`) | activity, user |
| shared | root package (`ApiError`, `GlobalExceptionHandler`) | all modules |

## 7. Forbidden dependencies (checked)

`ApplicationModules.verify()` enforces: no cycles, no internal-package access, whitelist
(`@ApplicationModule(allowedDependencies=...)`) compliance. `ModuleViolationDetectionTests`
proves the detector works: a fixture module (`beta`) referencing `alpha`'s internal class
fails with `Violations: Module 'beta' depends on non-exposed type ...AlphaInternal within
module 'alpha'`. Additionally, `spring-modulith-apt` performs compile-time verification.

## 8. Security architecture

OAuth2 Resource Server; `SecurityFilterChain` with stateless sessions, CSRF disabled for the
Bearer API, URL+scope authorization rules, custom `AuthenticationEntryPoint` (401) and
`AccessDeniedHandler` (403) returning the shared `ApiError` JSON contract. HMAC decoder
(`@ConditionalOnProperty app.security.jwt.secret-key`) for local/test; OIDC `issuer-uri`
decoder (Boot auto-config) for production. Verified end-to-end: 401/403/200 matrix
(`SecurityIntegrationTest`), real HS256 validation incl. wrong-key and expired rejection
(`JwtValidationIntegrationTest`).

## 9. Authentication flow

`Authorization: Bearer <JWT>` → BearerTokenAuthenticationFilter → JwtDecoder (signature,
expiry, issuer) → `JwtAuthenticationToken` → authorities from `scope` claims (`SCOPE_*`) →
URL rules → controller. Verified with real tokens minted with the test secret.

## 10. Authorization model

Scope-based (`hasAuthority("SCOPE_activity:write")` etc.). Full matrix in docs/security.md
§4; every rule covered by at least one integration test (401/403/200).

## 11. Event flow

`ActivityCreated/Updated/Deleted` published via `ApplicationEventPublisher` inside
`@Transactional` use cases; `WorkflowEventListener` consumes with
`@ApplicationModuleListener`. Verified: workflow entry created on activity create (API test
+ real Docker probe), synced on update, removed on delete; **after-commit delivery** and
**rollback non-delivery** proven by dedicated transaction-semantics tests.

## 12. Event transaction semantics (verified for Modulith 2.1.0)

`@ApplicationModuleListener` = `@Async` + `@Transactional(REQUIRES_NEW)` +
`@TransactionalEventListener(AFTER_COMMIT)` (verified via `javap` on the 2.1.0 artifact).
Without `@EnableAsync`: synchronous execution in the publishing thread after commit; each
listener in its own REQUIRES_NEW transaction; rollback of the publisher prevents delivery;
listener failure does not roll back the publisher's committed data; no durability; no
registry (events-core deliberately absent — its auto-configs require a registry bean).

## 13. Transaction boundaries

`@Transactional` on use-case methods only; `@Transactional(readOnly = true)` on queries;
`open-in-view: false`; listeners run in REQUIRES_NEW transactions. Optimistic locking
two-layer (application version check → 409; JPA `@Version` → 409). Verified:
`updateWithStaleVersionReturns409` (API) and `concurrentUpdateWithStaleVersionFails`
(persistence, real PostgreSQL).

## 14. Persistence boundaries

Domain repository interfaces in `domain/repository`; Spring Data + JPA entities strictly in
`infrastructure/persistence` (package-private Spring Data interfaces); mappers are the only
JPA↔domain bridge; `ddl-auto: validate` (schema owned by Flyway, 3 migrations). Verified by
`saveAndFindByIdRoundTrip` etc. against real PostgreSQL 16/17 via Testcontainers.

## 15. Test results (`mvnw.cmd -B verify`, Windows 11, JDK 21)

| Suite | Tests | Result |
|---|---|---|
| unit (domain) | 7 | ✅ 0 failures |
| application (use cases, Mockito) | 7 | ✅ 0 failures |
| architecture (Modulith verify + assertions) | 8 | ✅ 0 failures |
| architecture (violation detection) | 1 | ✅ 0 failures |
| integration: activity API | 11 | ✅ 0 failures |
| integration: JWT validation (real tokens) | 4 | ✅ 0 failures |
| integration: security matrix | 8 | ✅ 0 failures |
| integration: user API | 6 | ✅ 0 failures |
| integration: workflow events + tx semantics | 6 | ✅ 0 failures |
| persistence (Testcontainers PostgreSQL) | 4 | ✅ 0 failures |
| **Total** | **62** | **✅ BUILD SUCCESS** |

## 16. Architecture verification results

- `ApplicationModules.of(Application.class).verify()` ✅ passes (cycles / internals / whitelist).
- Dependency sets match the intended graph exactly ✅.
- Event wiring: workflow listens to exactly `ActivityCreated`, `ActivityUpdated`,
  `ActivityDeleted` ✅ (verified via `getEventsListenedTo`).
- Deliberate violation is detected and reported ✅.
- Compile-time APT verification active ✅ (no violations present).

## 17. Docker verification

- Multi-stage build (maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre-jammy,
  non-root `appuser`): ✅ image builds; `./mvnw` works on Linux; jar repackaged by Boot
  plugin.
- `docker compose up -d`: ✅ postgres healthy, redis healthy, app healthy in ~12s.
- Real-stack probes: 401 unauthenticated ✅ · 201 create user ✅ · 201 create activity ✅ ·
  workflow entry CREATED 18 ms later (event-driven) ✅ · PUT → ACTIVE v1 ✅ · workflow entry
  UPDATED ✅ · 403 FORBIDDEN JSON ✅ · `/actuator/prometheus` metrics ✅ · Flyway V1–V3
  migrations applied ✅.
- Redis (integration tests + compose): rate limiter 429s (THROTTLED/RATE_LIMITED +
  `Retry-After`) ✅ · cache populate/evict for `activities`, `workflow-entries`,
  `user-summaries` ✅ · fail-open meters/logs on connection failure ✅.

## 18. Dependency review

No Gradle files; no unnecessary starters; no Kafka/Elasticsearch/kubernetes;
no JWT library beyond what the resource-server starter provides; no CQRS/event-sourcing
framework; no per-module databases or apps. Redis is present and deliberate:
`spring-boot-starter-data-redis` (Lettuce) + `spring-boot-starter-cache` back the distributed
rate limiter and the read cache (both fail open, see docs/security.md §8 and
docs/architecture.md §8a) — Redis holds no source-of-truth data. The only version override:
Flyway 12.4.0 → 12.11.0 (Boot BOM's 12.4.0 rejects PostgreSQL 16.14/17.10 with
`Unsupported Database` — the support window predates those releases; 12.11.0 is the newest
12.x line).

## 19. Known trade-offs (implemented, deliberate)

| Trade-off | Where documented |
|---|---|
| Cross-module FKs (`activities.created_by→users.id`, `workflow_entries.activity_id→activities.id`) — single-DB coupling, extraction must drop them | docs/module-boundaries.md §5 |
| Separate JPA entity + mapper per aggregate (domain stays JPA-free; more code) | docs/architecture.md §3, §9 |
| In-process events: not durable, not external, no retries | docs/event-driven.md §3–5 |
| Synchronous listeners: listener failure can turn a committed request into a 500 | docs/transaction-boundaries.md §5 |
| HMAC local/test mode validates signatures but not issuer; impossible in prod (property absent there) | docs/security.md §3 |
| Module advices must be `@Order(HIGHEST_PRECEDENCE)` (Spring takes the first matching advice, not the most specific) | docs/architecture.md §9, docs/transaction-boundaries.md §5 |
| `saveAndFlush` in repository `save` (immediate INSERT so returned aggregates carry version/timestamps) | ActivityRepositoryAdapter javadoc |
| Redis fail-open for the limiter + read cache (availability over strict limiting; the rate limit silently disappears on outage — alert on `app.security.limiter.failopen`) | docs/security.md §8, docs/architecture.md §8a |
| Cache evict-before-commit (stale reads up to the 60s TTL; optimistic locking keeps updates safe) | docs/architecture.md §8a |

## 20. Future extraction candidates (documented, NOT implemented)

- Stage 2: `spring-modulith-events-jpa` registry or explicit outbox table + poller.
- Stage 3: `spring-modulith-events-kafka` / outbox → Kafka; at-least-once → idempotent
  consumers (listeners already idempotent) / inbox pattern.
- Stage 4: extract a bounded context (API isolation exists; event records carry primitives;
  FKs are the only schema coupling; security reuses the same IdP).
- Full path + triggers + when NOT to extract: docs/evolution-to-microservices.md.

---

**Implemented vs documented:** Kafka, outbox, registry, microservices, Redis cluster/sentinel:
**documented only**. Distributed rate limiting + read cache via a single Redis instance:
**implemented**. Everything in §1–§18 was built and verified in this project.
