# Practice Tasks — Spring Boot Modular Monolith Mastery

One master task: **"Prove I can build, extend, break, fix, and redesign this project on my own."**

Work through the subtasks in order. Each one has a **Done =** verification line — do not move on until it passes. Complete every subtask, then the capstone at the bottom.

---

## 0. Grounding (no code)

- [ ] Explain in one paragraph: what is a modular monolith and why this project chose it over microservices
  - Docs to read: `docs/architecture.md`, `docs/module-boundaries.md`, `docs/evolution-to-microservices.md`
- [ ] Draw the dependency graph between modules (`user`, `activity`, `workflow`, `security`, `shared`) — what may depend on what
- [ ] Explain the `api → application → domain ← infrastructure` layering and the direction of dependencies
  - Done = you can say which layer is allowed to import which, and name the exception

## 1. Build, run, config

- [ ] Run the app locally with Postgres (docker-compose up + `mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local`)
- [ ] Explain each key in `application.yml` vs `application-local.yml` vs `application-test.yml` vs `application-prod.yml`, and the merge/override order
- [ ] Change the local DB name to a new database, re-run, verify Flyway re-creates the schema
- [ ] Explain what `spring.jpa.hibernate.ddl-auto: validate` does and why it's NOT `create`
  - Done = app boots, tables exist, `flyway_schema_history` has V1–V3, and you can explain who owns the schema

## 2. Modules & layers (read-and-explain)

- [ ] For the `activity` module, walk each file and state its role: controller, use case, domain model, repository interface, JPA entity, entity mapper, domain event, exception handler
- [ ] Explain why the domain model (`Activity`) is separate from the JPA entity (`ActivityJpaEntity`) and what `ActivityEntityMapper` does
- [ ] Explain the `package-info.java` pattern and what is exported vs internal per module
- [ ] Explain `UserLookup` — how `user` exposes functionality to other modules without leaking its internals
  - Done = without looking, you can name every file in `activity` and its one-line purpose

## 3. Domain events & transactions

- [ ] Trace `ActivityCreated` from where it's raised to where it's consumed (see `docs/event-driven.md`, `docs/transaction-boundaries.md`)
- [ ] Explain when the event is published (before/after commit) and why that matters
- [ ] Explain the transaction boundary: which method holds the `@Transactional`, what happens if the transaction rolls back after the event was published
  - Done = you can answer: "if the DB insert fails, is the event consumer still called? why?"

## 4. Flyway & schema

- [ ] Read `V1__create_users.sql`, `V2__create_activities.sql`, `V3__create_workflow_entries.sql` and explain each constraint/type choice
- [ ] Write `V4__...sql` that adds a column to an existing table; boot, verify migration runs
- [ ] Break a migration on purpose (invalid SQL), boot, observe the failure, fix it
- [ ] Add a NOT NULL column to a table that already has rows — handle it properly (default or backfill)
  - Done = `flyway_schema_history` contains V4 with a correct checksum

## 5. Security & JWT

- [ ] Explain the two auth modes: `LocalJwtDecoderConfig` (HMAC secret) vs OAuth2 issuer-uri (prod) — read `docs/security.md`
- [ ] Explain the filter chain in `SecurityConfig`: which endpoints are public, which require auth, and why
- [ ] Call a protected endpoint with and without a token; observe `RestAuthenticationEntryPoint` vs `RestAccessDeniedHandler` behavior
- [ ] Generate a valid JWT with the local secret and call a protected endpoint with curl
- [ ] Forge a JWT with a wrong secret and explain the exact failure point
  - Done = you can explain the full request path: HTTP → filter chain → controller → `CurrentUserProvider`

## 6. Tests

- [ ] Run the full test suite: `mvnw.cmd test` — explain what each test class covers
  - Unit: `ActivityTest`, `WorkflowEntryTest`, use-case tests (mock repository)
  - Integration: `AbstractIntegrationTest` (Testcontainers), `ActivityApiIntegrationTest`, `SecurityIntegrationTest`, etc.
  - Architecture: `ApplicationModularityTests`, `ModuleViolationDetectionTests` (ArchUnit)
- [ ] Add one unit test to a use case that does NOT exist yet (e.g., invalid input → domain exception)
- [ ] Add one API integration test for a 404 path (e.g., GET non-existent activity)
- [ ] Write a test that intentionally violates a module boundary and prove ArchUnit catches it, then revert
  - Done = suite green, you can explain what Testcontainers spins up and why tests don't hit your local DB

## 7. Break it on purpose (JVM/Spring mechanics)

- [ ] N+1: add a `@OneToMany`/`@ManyToOne` fetch that causes N+1, observe it in logs, fix with join fetch
- [ ] `@Transactional` self-invocation: call a `@Transactional` method from inside the same bean, observe it silently not transactional, fix it
- [ ] Circular dependency: inject two beans into each other, observe the boot failure, fix it
- [ ] Proxy/lazy-loading trap: access a lazy association outside the transaction, observe `LazyInitializationException`, fix it
- [ ] Memory: generate a heap dump (`jcmd <pid> GC.heap_dump`), open it, find your object
  - Done = you can reproduce and explain each failure and its fix without help

## 8. Harden it

- [ ] Add a domain event consumer test for a workflow event (model it on `WorkflowEventIntegrationTest`)
- [ ] Make event handling idempotent (same event published twice → same result)
- [ ] Add request logging with a correlation/trace id; verify it appears in logs for one request
- [ ] Add a paginated GET endpoint (e.g., list activities) with a test
  - Done = new endpoint has unit + integration coverage and follows existing module conventions

## 9. Capstone — build your own module (biggest subtask)

Add a new module end-to-end WITHOUT copying blindly — make the design decisions yourself. Suggested domain: **`project`** (a project has a name, owner, and many activities).

- [ ] Design: write 3 sentences on boundaries — what `project` owns, what it needs from `user`, what it exposes to `activity`
- [ ] `V5__create_projects.sql` migration
- [ ] Domain: model + id + status, domain exceptions, repository interface
- [ ] Application: create/list/rename use cases
- [ ] Infrastructure: JPA entity + mapper + repository implementation
- [ ] API: controller + DTOs + exception handler, following existing conventions exactly
- [ ] Security: decide the endpoint rules and wire them
- [ ] Tests: unit + integration + one ArchUnit rule for the new module boundary
- [ ] Event: publish `ProjectCreated`, consume it somewhere meaningful (or justify why not)
  - Done = app boots, migration applies, endpoint works with JWT, suite green, module violates no boundaries

---

## Final self-check (after all subtasks)

Answer all of these without opening the project:

1. Who creates the schema and why doesn't Hibernate?
2. Why are domain models separate from JPA entities?
3. When is a domain event published relative to the DB commit, and why?
4. What exactly does `ddl-auto: validate` check and when does it fail?
5. Why is `package-info.java` important for module boundaries?
6. What does ArchUnit protect in this project?
7. Difference between `@RestControllerAdvice` and per-module exception handlers — when is each used?
8. How would you add a new module in 6 steps?

If you can answer all 8 fluently, this project is fully yours — you are writing idiomatic Spring, not "JavaScript in Java".
