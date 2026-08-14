# Module Boundaries

This document details the module-boundary rules, how they are enforced, and the database
coupling decisions.

## 1. The rules

1. **Modules must not access another module's internal implementation.**
   Internal = every package below the module root that is not declared a named interface.
2. **Controllers belong to their own module.** No global `controller/` package; a controller
   in `activity.api` only ever talks to `activity.application`.
3. **Repositories belong to their own module.** `ActivityRepositoryAdapter` is the *only* class
   that touches Spring Data inside the activity module; `SpringDataActivityRepository` is
   package-private on purpose.
4. **Domain logic belongs to the appropriate bounded context.** The workflow module has its
   own `WorkflowEntry` model — it never reuses activity's `Activity`.
5. **No global `controller` / `service` / `repository` / `entity` packages.**
6. **No circular module dependencies.** The graph is acyclic by design and verified.
7. **Prefer events for naturally asynchronous relationships.** Activity → Workflow is
   event-driven precisely to avoid `activity → workflow` coupling.
8. **No Kafka just to demonstrate events.** 9. **No Outbox until durable delivery is
   required.** 10. **No microservices until there is a real reason.** 11. **No abstractions
   without a concrete reason.** 12. **Prefer explicit code over clever abstractions.**
13. **Security must not leak into the domain.** 14. **Authentication and authorization are
   enforced at the application/API boundary.**

## 2. Enforcement mechanism

Three layers of enforcement, each stronger than the last:

1. **Package structure** — the layout itself makes most violations awkward (a global
   `repository/` package cannot even be created without violating the template).
2. **Spring Modulith verification (test time)** — `ApplicationModularityTests` calls
   `ApplicationModules.of(Application.class).verify()`, which fails the build on:
   - dependency cycles between modules;
   - access to another module's non-exposed (internal) packages;
   - dependencies outside the `allowedDependencies` whitelist.
   `ModuleViolationDetectionTests` proves the enforcement actually works: a fixture module
   (`beta`) that references another module's internal class (`alpha.internal`) **must** fail
   verification.
3. **Compile-time verification (optional bonus)** — `spring-modulith-apt` (a dependency of
   `spring-modulith-starter-core`) runs during compilation and reports violations as
   compiler errors/warnings.

## 3. Public APIs — the only doors

| Module | Exposed contract | Why it exists |
|---|---|---|
| user | `UserLookup` (root) | synchronous cross-module lookup (activity validates its creator) |
| activity | `api` named interface; `events` named interface | REST for clients; events for other modules |
| workflow | `api` named interface | read-only REST view |
| security | root package types | `CurrentUser`/`CurrentUserProvider` used by modules |
| shared | root package types | `ApiError` error contract |

Everything else in a module is internal: controllers are reachable by other modules only
through HTTP; repositories, entities, mappers and use-case services are not reachable at all.

## 4. What happens if someone breaks the rules

- Access to an internal package → `ApplicationModularityTests.moduleStructureIsValid()`
  fails with a message naming the offending module and type; `./mvnw verify` fails.
- A new module-to-module dependency outside the whitelist → same test fails
  ("allowed dependencies" violation).
- A cycle → verification fails with the cycle path.
- The compile-time processor additionally flags issues during `compile`/`test-compile`.

The intent: the *build* tells you when a boundary broke, before the code ships.

## 5. Database coupling (deliberate, documented)

The template uses **one database, one schema, three tables** with two cross-module foreign
keys:

```text
users(id) ◄── activities.created_by   (activity → user)
activities(id) ◄── workflow_entries.activity_id (workflow → activity, ON DELETE CASCADE)
```

**Why:** the modular monolith's superpower is transactional consistency; FKs give the
database the chance to enforce referential integrity that the application also checks.

**The cost:** these FKs are the first thing that must change if a bounded context is
extracted into a service (see [evolution-to-microservices.md](evolution-to-microservices.md),
step 5: "split the database"). In this template that is an accepted, documented trade-off —
the alternative (no FKs, application-level integrity only) is also valid and is what you'd
choose when extraction is already planned.

`ON DELETE CASCADE` on `workflow_entries.activity_id` means: deleting an activity removes its
workflow entries in the same transaction. The workflow listener on `ActivityDeleted` is then
a harmless no-op — the cascade and the event are complementary, not redundant.

## 6. How to add a new module

1. Create `com.example.app.<name>/` with `api`, `application`, `domain`, `infrastructure`.
2. Add `package-info.java` with `@ApplicationModule(allowedDependencies = {...})` listing
   exactly the other modules' public contracts you need.
3. Expose cross-module contracts via the root package or `@NamedInterface`.
4. Add the module to the expected-modules assertion in `ApplicationModularityTests`.
5. If other modules must consume your events, put them in a `@NamedInterface("events")`
   package and reference it as `<yourmodule>::events` in their whitelist.
