# Spring Boot Modular Monolith Template

A production-oriented **modular monolith** template for enterprise business applications:
one Spring Boot application, one JVM process, one deployable artifact, one PostgreSQL database —
with **strong business boundaries** that can later evolve into distributed services if business
and operational requirements justify it.

> **The core philosophy:** start with a modular monolith. Keep boundaries strong. Use Spring
> Security with standards-based JWT authentication. Use events where they provide real
> decoupling. Introduce Outbox/Kafka only when durable distributed event delivery is required.
> Extract microservices only when the business and operational requirements justify the complexity.

| Component | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 4.1.0, Maven |
| Modularity | Spring Modulith 2.1.0 (module detection + architecture verification + in-process transactional events) |
| Architecture | Domain-Driven Design, bounded contexts, hexagonal layering per module, event-driven module communication |
| Security | Spring Security 7.1, first-party JWT issuance (login/refresh/logout), RBAC |
| Security | Spring Security 7.1, first-party JWT issuance (login/refresh/logout), RBAC, Redis distributed per-IP rate limiting (fixed-window, fail-open) |
| Persistence | Spring Data JPA / Hibernate 7.4, PostgreSQL, Flyway 12 migrations |
| Caching | Redis read cache via Spring Cache (`@Cacheable`/`@CacheEvict`, 60s TTL, fail-open) |
| Observability | Spring Boot Actuator, Micrometer + Prometheus, Micrometer Tracing (OpenTelemetry), structured (ECS) logging in prod/test, request logging |
| Testing | JUnit 5, Mockito, MockMvc, Testcontainers (real PostgreSQL + Redis) |

---

## 1. What is a Modular Monolith?

A modular monolith is **one deployable application** (one JVM, one artifact, one database)
whose code is organized into **modules with explicit, enforced boundaries**. Each module owns
its domain logic, persistence and API; modules interact only through **public APIs** and
**events**.

| | Monolith | Modular Monolith | Microservices |
|---|---|---|---|
| Deployment units | 1 | 1 | N |
| Module boundaries | none (or convention only) | **enforced at build/test time** | network |
| Inter-module calls | any class can call any class | public API only | HTTP / messaging |
| Consistency | ACID everywhere | ACID within the app | eventual across services |
| Operational complexity | low | low | high |
| Scaling | vertical | vertical (+ read replicas) | horizontal per service |

A modular monolith keeps the simplicity and transactional guarantees of a monolith while
enforcing the discipline that makes a later extraction to microservices cheap.

## 2. What is a Bounded Context?

A **Bounded Context** is a *business/domain* boundary (from Domain-Driven Design): the boundary
inside which a domain model is consistent and unambiguous. "Activity", "Workflow" and "User"
are separate bounded contexts here — each has its own ubiquitous language, its own invariants,
its own persistence.

## 3. What is a Spring Modulith Module?

A **Spring Modulith application module** is the *technical mechanism* used to enforce
modularity inside the monolith: a package (e.g. `com.example.app.activity`) whose public API
is its root package plus declared **named interfaces**, with everything else internal.
Spring Modulith scans the compiled code and **verifies** the structure at test time:
no cycles, no access to another module's internals, no dependencies outside the declared
whitelist.

## 4. Bounded Context ≠ Spring Modulith Module

A bounded context is a business concept; a Spring Modulith module is an enforcement mechanism.
They are **not automatically equivalent**. This template deliberately maps them 1:1:

| Bounded Context (business) | Spring Modulith Module (technical) | Package |
|---|---|---|
| Activity | activity | `com.example.app.activity` |
| Workflow | workflow | `com.example.app.workflow` |
| User | user | `com.example.app.user` |

In a real system one bounded context could span several modules (or vice versa) — the mapping
is a design decision, not an identity. See [docs/architecture.md](docs/architecture.md).

## 5. Module structure

Every module follows the same layered layout, **business-first** (no global `controller/`,
`service/`, `repository/` packages anywhere):

```text
activity/
├── api/              REST controller, DTOs, module exception handling   (named interface "api")
├── application/      use cases / queries, transaction ownership
├── domain/           model, invariants, events, repository interfaces
│   ├── model/
│   ├── event/        ActivityCreated/Updated/Deleted                    (named interface "events")
│   └── repository/
└── infrastructure/
    └── persistence/  JPA entities, Spring Data, repository implementations
```

`shared/` is intentionally tiny: only the cross-cutting API error contract (`ApiError`,
technical exception handling). **No business types, DTOs or repositories live in `shared`.**

## 6. Module dependency rules

- Modules communicate only through **explicitly exposed public APIs** (root package or
  `@NamedInterface`) and **events**.
- Access to another module's internals (services, repositories, domain objects, JPA
  entities, infrastructure) is **forbidden**.
- Dependencies are declared per module with `@ApplicationModule(allowedDependencies = ...)`
  and enforced by Spring Modulith.

## 7. Public module APIs

| Module | Public API | Used by |
|---|---|---|
| user | `UserLookup` (root package) | activity (creator validation) |
| activity | `activity.api` (named interface "api"), `activity.domain.event` (named interface "events") | workflow (events only) |
| workflow | `workflow.api` (named interface "api") | HTTP clients |
| security | `CurrentUser`, `CurrentUserProvider` (root package) | activity, user |
| shared | `ApiError` (root package) | all modules |

## 8. Synchronous module communication

Cross-module *requests* go through public APIs only. Example: `CreateActivityUseCase`
(activity) validates the creator via `UserLookup` (user) — a synchronous call through the
user module's exposed contract. The dependency graph is acyclic and verified:

```text
workflow ──► activity (events)          activity ──► user (public API)
                                            ├──► security (CurrentUser)
                                            └──► shared (ApiError)
```

## 9. Event-driven communication

The activity module publishes lifecycle events (`ActivityCreated`, `ActivityUpdated`,
`ActivityDeleted`) via Spring's `ApplicationEventPublisher` inside its transaction.
The workflow module reacts with `@ApplicationModuleListener` — **without any dependency on
activity internals**:

```text
Activity module                  Workflow module
   │  publish event                     ▲
   │ ───────────────────────────────────┘ @ApplicationModuleListener
```

## 10. Domain Event vs Application Event vs Integration Event

| | Domain Event | Application Event | Integration Event |
|---|---|---|---|
| Meaning | something that happened in the domain | an application-level signal (may not be domain-meaningful) | an event crossing service boundaries |
| Where it lives | in the domain of its bounded context | anywhere in the app | outside the app (broker) |
| This template | `ActivityCreated` etc. (published inside use cases, after state change) | (not used separately — Spring's `ApplicationEventPublisher` is the transport) | not implemented — see §17 |
| Delivery | in-process, transactional | in-process | durable, at-least-once |

The template's events are **domain events transported as Spring application events**.
The term "integration event" is reserved for the future Kafka stage — see [docs/event-driven.md](docs/event-driven.md).

## 11. Transaction boundaries

Transactions are owned by the **application/use-case layer** — never controllers, never domain
objects:

```text
POST /activities → CreateActivityUseCase (@Transactional) → domain → repository → publish event
```

The event is published **inside** the transaction; listeners run **after commit**. Details
(rollback behavior, listener failure, optimistic locking) in
[docs/transaction-boundaries.md](docs/transaction-boundaries.md).

## 12. Spring Modulith event semantics (verified for 2.1.0)

In this template (no `@EnableAsync`, no event publication registry):

- `@ApplicationModuleListener` = `@TransactionalEventListener(AFTER_COMMIT)` +
  `@Transactional(REQUIRES_NEW)` + `@Async` (inert without `@EnableAsync`).
- Listeners run **synchronously, after the publisher's transaction commits**, each in its
  **own new transaction**.
- If the originating transaction rolls back, the event is **not delivered**.
- If a listener fails, its own transaction rolls back; the publisher's data **stays
  committed**; no automatic retry (in-process events are not durable).
- No Kafka, no outbox, no Event Publication Registry in this stage — see
  [docs/event-driven.md](docs/event-driven.md).

## 13. Spring Security architecture

The application is its **own token issuer**: `POST /api/v1/auth/login` (email + password)
returns an access token and a refresh token; every request validates the access token.
Spring Security sits at the API boundary only:

```text
Client → POST /auth/login → token pair → Bearer JWT → SecurityFilterChain → authorization rules → Controller → Use Case → Domain
```

## 14. JWT authentication

`Authorization: Bearer <JWT>`, issued by the application itself — HMAC secret in local/test,
RSA key pair in production. The `sub` claim is the user id; the `role` claim becomes the
`ROLE_<role>` authority. Refresh tokens are opaque, stored as SHA-256 hashes, rotated on
refresh and revoked on logout.

## 15. Authorization and authorities (RBAC)

| Endpoint | Requirement |
|---|---|
| `GET /api/v1/activities/**` | authenticated |
| `POST` / `PUT /api/v1/activities/**` | `ROLE_USER` or `ROLE_ADMIN` |
| `DELETE /api/v1/activities/**` | `ROLE_ADMIN` |
| `GET /api/v1/workflow-entries/**` | authenticated |
| `POST /api/v1/users` | `ROLE_ADMIN` |
| `GET /api/v1/users/**` | authenticated |
| `POST /api/v1/auth/login`, `/api/v1/auth/refresh` | public |
| `POST /api/v1/auth/logout` | authenticated |
| `/actuator/health`, `/actuator/info` | public |
| `/actuator/prometheus` | `SCOPE_prometheus` (dedicated scraper token) |

Expected JWT claims: `{"sub": "user-123", "role": "ADMIN"}`.
401 (unauthenticated) and 403 (forbidden) return the same JSON error contract.
See [docs/security.md](docs/security.md).

## 16. Why the User module owns authentication

The user module models **business user information** (name, email, status) and now also owns
first-party authentication: BCrypt password hashes, roles (RBAC) and refresh-token sessions.
Access tokens are issued by the security module's `JwtTokenService`. The first admin is
bootstrapped via `app.bootstrap.admin-email`/`admin-password` (see docs/security.md).

## 17. Why Kafka is not included

The in-process event mechanism of this stage is correct and sufficient: it is transactional,
synchronous and has no operational cost. Kafka earns its complexity only when events must
survive process crashes and reach **external** consumers. The evolution path (outbox → Kafka →
extracted services) is documented in [docs/evolution-to-microservices.md](docs/evolution-to-microservices.md).

## 18. Event durability limitations

In-process events do **not** survive a crash between commit and listener execution, are
**not** visible outside the JVM, and are **not** retried. This template does not pretend
otherwise — see [docs/event-driven.md](docs/event-driven.md) for exact guarantees and the
outbox upgrade path.

## 19. Idempotent consumers

The workflow listener operations are written **idempotently** (find-or-create, delete-if-exists),
so duplicate event delivery is harmless. This prepares for at-least-once delivery semantics
of the future Kafka stage. If workflows become stateful and expensive, the Inbox Pattern is
the documented next step (not implemented — not needed yet).

## 20. How Outbox can be introduced later

Add `spring-modulith-events-jpa` (the Event Publication Registry stores events in a table in
the same transaction; a scheduler retries failed publications), or a dedicated outbox table
+ poller (as in a typical production setup). Details and trade-offs:
[docs/evolution-to-microservices.md](docs/evolution-to-microservices.md).

## 21. How a bounded context could eventually become a microservice

Extraction checklist: expose the module's behavior over HTTP (its API is already isolated),
replace in-process events with Kafka events (the events already carry primitives only), split
the database (drop the cross-module FK), move security config to the new service, keep the
token issuer. Each step is reversible; the module boundaries make it cheap.
See [docs/evolution-to-microservices.md](docs/evolution-to-microservices.md).

---

## Quick start

Prerequisites: JDK 21, Docker (Desktop), and (on Windows) `mvnw.cmd`; on Linux/macOS `./mvnw`.

### Option A — full stack with Docker Compose

```bash
docker compose up --build
# app on http://localhost:8080 (local profile, HMAC JWT mode), PostgreSQL on 5432, Redis on 6379, RedisInsight UI on http://localhost:5540
```

### Option B — run locally against your own PostgreSQL + Redis

```bash
docker compose up -d postgres redis
./mvnw compile spring-boot:run -Dspring-boot.run.profiles=local        # Linux/macOS
mvnw.cmd compile spring-boot:run -Dspring-boot.run.profiles=local     # Windows
```

> `compile` is intentional: it generates `git.properties`/`build.properties` for
> `/actuator/info` and triggers devtools restart on code changes. See
> [docs/observability.md](docs/observability.md).

### Option C — production-like run

```bash
export DB_URL=jdbc:postgresql://localhost:5432/modular_monolith DB_USERNAME=postgres DB_PASSWORD=postgres \
       JWT_PRIVATE_KEY="$(cat private.pem)" JWT_PUBLIC_KEY="$(cat public.pem)"
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

### Get a token and call the API (local mode)

```bash
# 1. create a user with a password (requires ROLE_ADMIN; mint an ADMIN token for an
#    existing admin user id, or bootstrap the first admin via BOOTSTRAP_ADMIN_EMAIL/PASSWORD)
TOKEN=$(python scripts/mint-local-jwt.py --sub <admin-user-id> --role ADMIN)
curl -s -X POST http://localhost:8080/api/v1/users \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","password":"s3cret-pass","role":"USER"}'
# -> 201 {"id":"...","name":"Alice",...}   (note the user id)

# 2. log in with email + password -> access + refresh tokens
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"s3cret-pass"}'
# -> 200 {"accessToken":"...","refreshToken":"...","tokenType":"Bearer","expiresIn":900}

# 3. call the API with the access token
curl -s -X POST http://localhost:8080/api/v1/activities \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Team retro","description":"Monthly retro"}'
# -> 201 {...}

# 4. refresh (rotates the refresh token) and logout (revokes it)
curl -s -X POST http://localhost:8080/api/v1/auth/refresh -H "Content-Type: application/json" \
  -d '{"refreshToken":"..."}'
curl -s -X POST http://localhost:8080/api/v1/auth/logout -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" -d '{"refreshToken":"..."}'
```

The mint script (`scripts/mint-local-jwt.py`) is a **development-only** convenience
(HS256 + the local secret). Production tokens are issued by the application itself (RSA).

## Build and test

```bash
./mvnw clean verify      # Linux/macOS (and the CI / Docker build)
mvnw.cmd clean verify    # Windows
```

`verify` runs: unit tests (domain), application tests (use cases), **architecture
verification** (Spring Modulith, including a test that proves violations are detected),
API/security tests, and integration tests against a real PostgreSQL container via
Testcontainers (Docker required).

> Note: on this Windows setup, `./mvnw` works in git-bash; use `mvnw.cmd` in `cmd`.

## Project layout

```text
├── pom.xml  mvnw  mvnw.cmd  .mvn/
├── Dockerfile  docker-compose.yml  scripts/mint-local-jwt.py
├── README.md
├── docs/
│   ├── architecture.md               module map, layering, BC vs module, risks
│   ├── module-boundaries.md          rules, public APIs, enforcement, DB coupling
│   ├── event-driven.md               event types, semantics (verified), evolution stages
│   ├── transaction-boundaries.md     tx ownership, event timing, optimistic locking
│   ├── security.md                   full security architecture and operations guide
│   ├── observability.md              metrics, tracing, logging, request logging, info
│   ├── evolution-to-microservices.md outbox/Kafka/extraction path, idempotency
│   └── architecture-verification-report.md   versions, results, review, trade-offs
└── src/main/java/com/example/app/
    ├── Application.java
    ├── activity/  workflow/  user/   bounded contexts
    ├── security/                      Resource Server, JWT, 401/403, CurrentUser
    └── shared/                        ApiError + technical exception handling only
```

## Documentation

| Doc | Covers |
|---|---|
| [architecture.md](docs/architecture.md) | module map, layering, BC vs Modulith module, dependency graph, risks & trade-offs |
| [module-boundaries.md](docs/module-boundaries.md) | rules, public APIs, forbidden dependencies, enforcement, DB coupling trade-offs |
| [event-driven.md](docs/event-driven.md) | domain/application/integration events, verified semantics, idempotency, evolution stages |
| [transaction-boundaries.md](docs/transaction-boundaries.md) | tx ownership, event timing, rollback, optimistic locking |
| [security.md](docs/security.md) | authentication, authorization, JWT, 401 vs 403, local dev, production IdP |
| [observability.md](docs/observability.md) | metrics, tracing, structured/request logging, Prometheus access, build metadata |
| [evolution-to-microservices.md](docs/evolution-to-microservices.md) | outbox → Kafka → extraction, idempotent consumers, when NOT to extract |
| [architecture-verification-report.md](docs/architecture-verification-report.md) | the post-implementation review: versions, verification results, trade-offs |
