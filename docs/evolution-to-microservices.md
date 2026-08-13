# Evolution to Microservices

> Everything in this document is **future evolution**. None of it is implemented in this
> template. The template's value is that these steps stay cheap because the boundaries
> already exist.

## 1. The staged path

```text
Stage 1 (implemented)
Spring Boot + Modular Monolith + Spring Modulith + Spring Security + in-process events

Stage 2 (when durability is required)
+ durable event publication (Event Publication Registry / outbox table)

Stage 3 (when external consumers are required)
+ Kafka, external consumers (at-least-once → idempotent consumers / inbox)

Stage 4 (when operational requirements justify it)
bounded context → independent microservice
```

## 2. Stage 2 — durable event publication (outbox)

**Trigger:** events must survive a crash between commit and listener execution, or must be
retried on failure.

**Option A — Spring Modulith Event Publication Registry** (least code):
add `spring-modulith-events-jpa`. The registry writes each published event into an
`event_publication` table **in the same transaction** as the business change; a
`@TransactionalEventListener`-based completion marks entries done when listeners succeed;
failed/incomplete publications are retried (including on restart via
`spring.modulith.events.republish-outstanding-events-on-restart`).

**Option B — explicit outbox table + poller** (most control):
an `outbox_events` table written in the business transaction; a `@Scheduled` poller sends
events to the broker and marks them published only after broker acknowledgement.
Crash between ACK and mark = duplicate = at-least-once.

Trade-offs: Option A is less code but ties durability to Modulith's lifecycle; Option B is
the classic pattern (this exact recipe was verified in the project's earlier lesson series:
Kafka stopped → API still returns 201 → event row waits → broker restarted → row flips to
published). Do **not** add an outbox before this requirement exists.

## 3. Stage 3 — Kafka

**Trigger:** consumers exist outside this JVM (another service, another team, a data lake).

- The event records already carry primitives only → they serialize to JSON without change.
- Spring Modulith's `spring-modulith-events-kafka` (externalization) can route
  `@ApplicationModuleListener` events to a Kafka topic with minimal code.
- **Delivery semantics become at-least-once**: consumers must be idempotent. The workflow
  listeners already are (find-or-create, delete-if-exists). For non-idempotent work,
  apply the **Inbox Pattern**: a `processed_events` table with a unique event id, checked
  and written in the consumer's transaction.

## 4. Stage 4 — extracting a bounded context

The extraction checklist (each step reversible, each step testable):

1. **API**: the module's REST API is already isolated (`activity.api`); deploy it behind
   its own route in the new service.
2. **Synchronous cross-module calls**: `UserLookup` becomes an HTTP/interface call to the
   user service (or a cached snapshot). The activity module only knows the *contract* —
   which is already the case today.
3. **Events**: in-process events become Kafka integration events (Stage 3). Consumers
   (workflow) keep the same event records.
4. **Ownership**: the extracted service owns its schema and its deployment; the monolith
   keeps the rest.
5. **Database split**: drop the cross-module foreign keys (`activities.created_by →
   users.id`, `workflow_entries.activity_id → activities.id`). This is the only schema
   change — the application-level integrity checks already exist.
6. **Security**: the new service keeps the same Resource Server configuration (same IdP,
   same issuer) — security does not need to be distributed, it stays delegated to the IdP.
7. **Operational cost appears now**: network failures, retries, observability across
   services, data consistency (eventual), deployment coordination. This is the price you
   pay — and the reason you should not pay it before the business requires it.

## 5. When NOT to extract

- When the "service" would have a single instance and a single database anyway.
- When the only driver is team size (better: keep the monolith, invest in CI).
- When the transaction boundary spans contexts *inside* a single request
  (a monolith gives you ACID; a split forces sagas).
- When the operational team cannot yet run Kafka, service mesh or per-service observability.

The template's philosophy: **extraction is a capability, not a goal**. The module
boundaries exist so the decision can be made when the business case is real — and so the
cost of the wrong decision stays low.

## 6. Security evolution

Security evolves independently and *earlier* in practice:

```text
local HMAC mode ──► external Identity Provider (OIDC discovery) ──► per-service enforcement
```

The application is already provider-agnostic (standard `scope` claims, issuer-uri
configuration). Extracting a service does not change its security model: same IdP, same
JWT validation, same authorization rules. Never "distribute" authentication itself — the
IdP stays the single authority, and each service enforces the same standards at its own
boundary.
