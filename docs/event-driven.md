# Event-Driven Architecture

This document explains the event model of the template: the three event categories, the
exact semantics of the chosen Spring Modulith version (verified against the 2.1.0 sources),
and the staged evolution toward durable, distributed events.

## 1. Domain Events vs Application Events vs Integration Events

These terms are **not interchangeable**.

| | Domain Event | Application Event | Integration Event |
|---|---|---|---|
| **What it represents** | a fact that happened in the domain, expressed in the ubiquitous language ("an activity was created") | any signal inside the application, not necessarily domain-meaningful (e.g. "cache warmed up") | a fact leaving the application boundary for other services |
| **Where it belongs** | the domain layer of its bounded context | application layer / infrastructure | outside the app (broker, queue) |
| **Producer** | the aggregate / use case after a state change | any application component | a publisher service |
| **Transport in this template** | Spring `ApplicationEventPublisher` (in-process) | same mechanism (not used separately here) | not implemented (Kafka in a later stage) |
| **Guarantees** | transactional, in-process | in-process | durable, at-least-once |

The three events in this template (`ActivityCreated`, `ActivityUpdated`, `ActivityDeleted`)
are **domain events**. They are transported with Spring's application-event mechanism, which
is exactly what makes them *also* application events at the technical level. The term
**integration event** is deliberately reserved for the future Kafka stage; calling the
in-process events "integration events" would overstate their guarantees.

## 2. Event catalogue

| Event | Published by | Carries | Consumed by |
|---|---|---|---|
| `ActivityCreated(activityId, name)` | `CreateActivityUseCase` (after save, same tx) | primitives only | workflow: creates `WorkflowEntry` (status CREATED) |
| `ActivityUpdated(activityId, name, status)` | `UpdateActivityUseCase` (after save, same tx) | primitives only | workflow: syncs name, status UPDATED |
| `ActivityDeleted(activityId)` | `DeleteActivityUseCase` (after delete, same tx) | primitives only | workflow: deletes the entry |

Events carry **simple types only** — never domain objects. A consumer therefore depends on
the event contract, not on the producer's internals, which is what makes later extraction
cheap (the same record serializes to JSON/Kafka without change).

## 3. Verified event semantics (Spring Modulith 2.1.0)

The following was verified against the actual 2.1.0 artifact sources (`javap` on
`spring-modulith-events-api`): `@ApplicationModuleListener` is a composed annotation of

```java
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
@TransactionalEventListener   // default phase: AFTER_COMMIT
```

Consequences for this template (which does **not** enable `@EnableAsync` and does **not**
include the event publication registry):

1. **Synchronous by default.** Listeners run in the publishing thread, right after the
   publisher's transaction commits. `@Async` is inert without `@EnableAsync`.
2. **After-commit delivery.** Events published in a transaction are delivered only if that
   transaction commits. If it rolls back, the event is **not** delivered (verified by the
   integration test `eventPublishedInRolledBackTransactionIsNotDelivered`).
3. **Own transaction.** Each listener executes in a new transaction (`REQUIRES_NEW`), so
   the listener's writes are independent of the publisher's committed data.
4. **Listener failure.** The listener's own transaction rolls back; the publisher's data
   stays committed. Because delivery is synchronous, the exception propagates to the
   publishing thread after commit — the HTTP response may become a 500 even though the
   business transaction succeeded. This is the price of simplicity and is documented
   behavior; options: `@EnableAsync`, or the durable registry (next stage).
5. **No durability.** Without the Event Publication Registry (it is `@ConditionalOnBean`
   and no repository implementation is on the classpath), events are pure in-process Spring
   events: a crash between commit and listener execution loses the event; nothing is
   persisted; no automatic retries; nothing leaves the JVM.
6. **`spring-modulith-events-core` is deliberately NOT on the classpath.** Verified from the
   2.1.0 artifacts: its auto-configurations (staleness monitor, externalization, async
    defaults) assume a durable `EventPublicationRegistry` bean and fail startup without one.
   The template therefore ships only `spring-modulith-events-api` (the `@ApplicationModuleListener`
   annotation, which references Spring types only) — Stage 2 adds the registry intentionally.

## 4. What an in-process event is NOT

- Not a message queue: no ordering guarantees across publishers, no consumer groups.
- Not durable: nothing survives a process crash.
- Not visible outside the JVM.
- Not retried: a failed listener stays failed (unless your code retries).

If the business needs any of those properties, the next stage is required — not optional
decoration.

## 5. Idempotency

Even in-process delivery is effectively once-per-commit, but consumers are written
**idempotently** anyway, because the future Kafka stage delivers at-least-once:

- `onActivityCreated`: find-or-create → a duplicate delivery is a no-op.
- `onActivityUpdated`: reconstruct-if-missing then sync → out-of-order and duplicate
  deliveries converge to the same state.
- `onActivityDeleted`: delete-if-exists → duplicate deletes are no-ops.

The Inbox Pattern (a per-consumer processed-event log) is **not** implemented: for these
simple sync operations idempotency is enough. When a consumer performs expensive or
non-idempotent work, the inbox is the documented upgrade
([evolution-to-microservices.md](evolution-to-microservices.md)).

## 6. The evolution path (documented, not implemented)

```text
Stage 1  (THIS TEMPLATE)
Domain Event ──► Spring Modulith ──► in-process consumer
   transactional, synchronous, no durability, zero infrastructure

Stage 2  (when durability is required)
Domain Event ──► Event Publication Registry / Outbox (same DB transaction)
   events persisted atomically with business state; retries on restart

Stage 3  (when external consumers are required)
Outbox ──► Kafka ──► external consumers
   at-least-once delivery; consumers must be idempotent or use the inbox

Stage 4  (when operational requirements justify it)
Bounded Context ──► independent microservice
```

Each stage is a strict superset of the previous one; the code changes are localized
(publisher + transport), because the event records and consumer semantics were designed for
it from the start.
