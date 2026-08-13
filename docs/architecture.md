# Architecture

This document describes the architecture of the modular monolith template:
module map, layering, bounded contexts vs Spring Modulith modules, dependency graph,
and the known trade-offs.

## 1. Module map

```text
┌──────────────────────────────────────────────────────────────┐
│                     com.example.app                           │
│                                                              │
│   ┌──────────┐      events (ActivityCreated/Updated/Deleted) │
│   │ activity │──────────────────────────────┐                │
│   │          │                              ▼                │
│   │  ┌───────┴──────┐          ┌──────────────────────┐      │
│   │  │ user (public │          │      workflow        │      │
│   │  │   API only)  │          │ (reacts to events,   │      │
│   │  └──────────────┘          │  never calls internals)      │
│   └────────────────────────────┴──────────────────────┘      │
│                                                              │
│   security (OAuth2 Resource Server, CurrentUser)             │
│   shared   (ApiError, technical error handling)              │
└──────────────────────────────────────────────────────────────┘
```

## 2. Bounded Context vs Spring Modulith Module

- **Bounded Context (DDD)**: a *business* boundary. Inside it, the domain model is
  consistent: names mean exactly one thing, invariants hold, the ubiquitous language is
  unambiguous. "Activity", "Workflow" and "User" are bounded contexts.
- **Spring Modulith module**: a *technical* boundary. A package whose root + named
  interfaces form the public API; everything else is internal. Spring Modulith *verifies*
  the boundaries from the compiled code (cycles, internal access, dependency whitelists).

This template maps them 1:1 because the example domains are small. The mapping is a design
decision: a real system might split one bounded context into several modules (e.g. an
`activity` read model + `activity` command side) or merge small contexts. The technical
enforcement (Modulith) stays independent of the business modeling (DDD).

## 3. Layering inside each module

Every module follows the same dependency direction — inner layers know nothing about outer
layers:

```text
api (REST, DTOs, validation, module error mapping)
   │  depends on
   ▼
application (use cases / queries, @Transactional boundaries)
   │  depends on
   ▼
domain (models, invariants, events, repository interfaces)
   ▲
   │  implements
   │
infrastructure (JPA entities, Spring Data, repository implementations)
```

Rules enforced by construction (package structure + Modulith verification):

- `domain` imports no Spring MVC, no JPA, no Spring Security, no HTTP. The domain model is
  plain Java (see the `@Version`-free, annotation-free `Activity` class).
- `application` imports `domain` (+ cross-cutting abstractions like `CurrentUser`).
- `infrastructure` imports `domain` (implements the repository interfaces) — never the
  reverse.
- `api` imports `application` and `domain` exceptions; it contains no business logic.
- JPA entities are never exposed outside `infrastructure.persistence`; REST responses are
  DTOs mapped from domain objects.

## 4. Explicit dependency graph (enforced)

```text
                 ┌──────────────┐
                 │     User     │
                 │ public API   │
                 └──────┬───────┘
                        │
        ┌───────────────┼────────────────┐
        │                                │
        ▼                                ▼
   Activity (module)                (no direct dependency from workflow to user)
        │
        │ ActivityCreated / ActivityUpdated / ActivityDeleted (named interface "events")
        ▼
   Workflow (module)
```

Declared whitelists (`@ApplicationModule(allowedDependencies = ...)`):

| Module | Allowed dependencies | Note |
|---|---|---|
| activity | `user`, `security`, `shared` | user root API only (`UserLookup`) |
| workflow | `activity::events`, `shared` | consumes events, never internals |
| user | `security`, `shared` | |
| security | `shared` | |
| shared | — | no dependencies |

`verify()` additionally forbids: cycles, access to any non-exposed package of another
module, and (via the whitelist) any dependency not listed above. See
`ApplicationModularityTests` and `ModuleViolationDetectionTests` in the test sources.

## 5. Public module APIs

- **user** — root package: `UserLookup` interface + `UserLookup.Summary` record.
  Cross-module synchronous lookups go through this contract only.
- **activity** — `api` named interface (REST + DTOs) and `events` named interface
  (the three lifecycle events). The REST API is consumed by clients; other modules
  consume only events.
- **workflow** — `api` named interface (read-only REST view of workflow entries).
- **security** — root package: `CurrentUser`, `CurrentUserProvider`. The single
  technical abstraction every module may use; no module ever touches
  `SecurityContext`/`Jwt`/`Authentication` directly.
- **shared** — root package: `ApiError` + `GlobalExceptionHandler` + `ConflictException`.
  Cross-cutting *technical* concerns only.

## 6. Security architecture (summary)

See [security.md](security.md) for the full treatment. In short: the application is an
OAuth2 Resource Server. JWT validation happens in the security filter chain; authorization
rules live in `SecurityConfig` (URL + scope based); controllers translate the principal to
`CurrentUser`; use cases receive `CurrentUser` as a plain parameter. The domain never sees
security types.

## 7. Event flow (summary)

See [event-driven.md](event-driven.md). In short: use cases publish domain events through
`ApplicationEventPublisher` inside their transaction; `@ApplicationModuleListener`
(Spring Modulith 2.1.0) delivers them **synchronously after commit**, each listener in its
own `REQUIRES_NEW` transaction. No broker, no outbox in this stage.

## 8. Persistence boundaries

- Domain repository *interfaces* live in `domain/repository`; implementations in
  `infrastructure/persistence` (Spring Data + mappers).
- One PostgreSQL database, one schema, Flyway-owned DDL (`ddl-auto: validate`).
- Cross-module foreign keys exist (`activities.created_by → users.id`,
  `workflow_entries.activity_id → activities.id`): a deliberate single-database trade-off,
  documented in [module-boundaries.md](module-boundaries.md).
- Optimistic locking via `@Version` on mutable aggregates (activities, workflow entries).

## 9. Known trade-offs and risks

| Decision | Trade-off |
|---|---|
| One database, cross-module FKs | Simple transactions and joins now; extraction must remove FKs later |
| Separate JPA entity + mapper per aggregate | More code than annotating the domain directly, but the domain stays JPA-free and testable without Hibernate |
| In-process events (no outbox/Kafka) | Zero operational cost, transactional; not durable across crashes, not external |
| Synchronous listeners (no `@EnableAsync`) | Deterministic, easy to test; a listener failure propagates to the publisher's thread (after commit) |
| HMAC secret mode for local/test | Real signature validation without an IdP; no issuer validation in that mode (impossible in prod: the property is absent there) |
| Scope-based authorization | Simple, standard; fine-grained claims (e.g. per-tenant) would need a custom converter |
| Shared `GlobalExceptionHandler` catches `Exception` | Guarantees no stack-trace leaks; module advices MUST be `@Order(HIGHEST_PRECEDENCE)` because Spring's exception resolution takes the first matching advice, not the most specific one (verified: without `@Order`, the shared catch-all wins and business errors become 500s) |

## 10. Non-goals (explicitly not implemented)

Microservices, Kafka, Redis, Elasticsearch, Kubernetes manifests, Event Sourcing, CQRS
infrastructure, Sagas, distributed transactions/locks, service discovery, API gateway,
OAuth2 Authorization Server, custom IdP, custom JWT, per-module databases, per-module
applications. These are discussed as future evolution only
([evolution-to-microservices.md](evolution-to-microservices.md)).
