# Transaction Boundaries

## 1. Ownership

Transactions are owned by the **application/use-case layer** — the only layer allowed to
annotate with `@Transactional`:

| Use case | Transaction | Notes |
|---|---|---|
| `CreateActivityUseCase.execute` | read-write | creator lookup (read) + insert + event registration |
| `UpdateActivityUseCase.execute` | read-write | load + version check + update + event registration |
| `DeleteActivityUseCase.execute` | read-write | load + delete + event registration |
| `GetActivityQuery.findById` | read-only | `@Transactional(readOnly = true)` |
| `CreateUserUseCase.execute` | read-write | duplicate check + insert |
| `UserLookupService.findById` | read-only | |
| `WorkflowEntryApplicationService.on*` | read-write | listener's own `REQUIRES_NEW` transaction |

**Never** in controllers, **never** in domain objects, **never** in repositories
(repositories participate in the caller's transaction).

## 2. The write path, step by step

```text
POST /api/activities
   │
   ▼
ActivityController.create()            no transaction
   │
   ▼
CreateActivityUseCase.execute()        @Transactional: BEGIN
   │   ├─ UserLookup.findById(...)     read (user module, joins the same tx)
   │   ├─ Activity.create(...)         domain logic, no I/O
   │   ├─ ActivityRepository.save()    INSERT activities
   │   └─ eventPublisher.publishEvent(new ActivityCreated(...))
   │                                     event registered for after-commit delivery
   └─ method returns                   COMMIT  ──► listeners run (see §4)
```

The event is published **inside** the transaction — this is what makes the event and the
business change atomic from the application's point of view.

## 3. Rollback behavior

- Any runtime exception thrown by the use case (or a repository constraint violation)
  rolls the whole transaction back: no row, no event.
- The event was never delivered to listeners, because delivery happens only after commit
  (verified by `eventPublishedInRolledBackTransactionIsNotDelivered`).
- Listeners run **after** the commit, so their failures cannot roll back the publisher's
  transaction — but see §5.

## 4. Event listener execution

`@ApplicationModuleListener` (Spring Modulith 2.1.0) = `@TransactionalEventListener(AFTER_COMMIT)`
+ `@Transactional(REQUIRES_NEW)` + `@Async` (inert here).

```text
publisher tx:  BEGIN ... COMMIT ──► AFTER_COMMIT listeners invoked (same thread, sync)
listener tx:   BEGIN (REQUIRES_NEW) ... COMMIT
```

The workflow listener therefore writes its `workflow_entries` row in a **separate, fresh
transaction** after the activity insert committed. If the listener fails, its transaction
rolls back and the workflow entry is not written — the activity remains created.

## 5. Failure semantics (documented behavior, not a bug)

Because delivery is synchronous in the publishing thread:

- listener exception → propagates to the controller → HTTP 500, **although the activity was
  committed**.
- This is the standard trade-off of synchronous in-process events: simple consistency,
  but the secondary functionality can make the primary request "fail" after succeeding.

Mitigations (choose deliberately, do not stack them blindly):

1. accept it and monitor listener failures (fine for stub workflows like this one);
2. `@EnableAsync` — listeners run on a separate executor; failures are logged, the HTTP
   response is unaffected (at the cost of losing guaranteed listener execution on commit);
3. move to the durable event publication stage (outbox/registry) with retries
   ([event-driven.md](event-driven.md)).

**Related gotcha (verified):** Spring's exception resolution iterates `@RestControllerAdvice`
beans *in order* and takes the first advice that can handle the exception — specificity only
applies within one advice. The module advices therefore carry
`@Order(Ordered.HIGHEST_PRECEDENCE)` so they are consulted before the shared
`GlobalExceptionHandler`'s `Exception` catch-all; without it, business errors would be
resolved as generic 500s.

## 6. Optimistic locking

Mutable aggregates carry a `@Version` column (activities, workflow entries). Two layers:

1. **Application check**: `UpdateActivityUseCase` compares the client-supplied version with
   the loaded one and fails fast with `ConflictException` → HTTP 409.
2. **Database check**: Hibernate compares `@Version` on flush/commit; a concurrent write
   throws `ObjectOptimisticLockingFailureException` → mapped to HTTP 409 by
   `GlobalExceptionHandler`.

The application check gives a friendly, immediate 409 for stale clients; the JPA check
closes the check-then-write race window. Verified end-to-end by
`updateWithStaleVersionReturns409` (API) and `concurrentUpdateWithStaleVersionFails`
(persistence).

## 7. Read paths

Reads use `@Transactional(readOnly = true)` (a hint to Hibernate and the connection pool;
no writes allowed). `open-in-view: false` is set globally — no lazy-loading outside
transactions, which forces explicit fetch planning in real code.
