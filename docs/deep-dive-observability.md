# Deep Dive: Observability

How this application is observed: business counters that fire only on committed
transactions, one structured log line per HTTP request, W3C trace context with OTLP export,
public health/info probes, and a scope-gated Prometheus endpoint.

Companion doc: `docs/observability.md` (the *what* — stack table, meter catalog, run
instructions). This document is the *how* — code paths, algorithms, and the reasoning
behind each non-obvious choice.

---

## 1. Overview

| Name | Path | Role |
|---|---|---|
| `AfterCommitMetrics` | `shared/AfterCommitMetrics.java` | Deferred counter increment that fires only when the surrounding transaction commits |
| `RequestLoggingFilter` | `security/web/RequestLoggingFilter.java` | One structured INFO line per HTTP request (ECS-aligned fields: method, path, status, duration, user id, outcome, error type) |
| `SecurityConfig` | `security/config/SecurityConfig.java` | Registers the filter in the chain and authorizes the actuator endpoints |
| `application.yml` | `src/main/resources/application.yml` | Actuator exposure, health probes, tracing sampling, OTLP endpoint, info contributors |
| `application-local.yml` | `src/main/resources/application-local.yml` | Human-readable logs, DEBUG SQL + TRACE bind values, `format_sql` |
| `application-test.yml` | `src/main/resources/application-test.yml` | ECS JSON logs, 100% sampling |
| `application-prod.yml` | `src/main/resources/application-prod.yml` | ECS JSON logs, 10% sampling, fail-fast `OTLP_ENDPOINT` (standard `OTEL_EXPORTER_OTLP_*` env vars for endpoint/headers) |
| `pom.xml` | `pom.xml` | Observability dependency stack + the surefire metrics-export flag |
| `CreateActivityUseCase` | `activity/application/usecase/CreateActivityUseCase.java` | Consumer: increments `app.activities.lifecycle{action=created}` after commit |
| `UpdateActivityUseCase` | `activity/application/usecase/UpdateActivityUseCase.java` | Consumer: increments `{action=updated}` after commit |
| `DeleteActivityUseCase` | `activity/application/usecase/DeleteActivityUseCase.java` | Consumer: increments `{action=deleted}` after commit |
| `LoginUseCase` | `user/application/usecase/LoginUseCase.java` | Consumer: counts `app.auth.logins` — success after commit, failure immediately |
| `WorkflowEntryApplicationService` | `workflow/application/listener/WorkflowEntryApplicationService.java` | Consumer: counts `app.workflow.entries` after the listener's own commit |
| `WorkflowEventListener` | `workflow/application/listener/WorkflowEventListener.java` | Modulith listener that calls the service above |
| `RoleJwtAuthenticationConverter` | `security/jwt/RoleJwtAuthenticationConverter.java` | Maps `scope` → `SCOPE_*` and allow-listed `role` → `ROLE_*` authorities |
| `JwtTokenService` | `security/jwt/JwtTokenService.java` | App token issuer — tokens carry `role` but never `scope` |
| `ObservabilityIntegrationTest` | `src/test/.../integration/ObservabilityIntegrationTest.java` | 15 tests proving every behavior cited in this document |
| `ReadinessIntegrationTest` | `src/test/.../integration/ReadinessIntegrationTest.java` | readiness group tracks `db`: DB outage → 503/DOWN, liveness stays UP |
| `ProdTelemetryConfigTest` | `src/test/.../integration/ProdTelemetryConfigTest.java` | OTLP Authorization header reaches the exporter config; missing `OTLP_ENDPOINT` fails prod startup |
| `prometheus.yml`, `otelcol-config.yml`, `observability-up.sh`, `mint-local-jwt.py`, `mint-rsa-jwt.py`, `observability-smoke-test.sh`, `grafana/provisioning/alerting/alert-rules.yml` | `observability/`, `scripts/` | Local UI stack: scrape config (+ collector self-metrics job), trace pipeline, token minting (HS256 local / RS256 prod), smoke test, Grafana alert rules |

---

## 2. Declarative knowledge

### 2.1 The problem: counters are not transactional, and the filter position decides `user_id`

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│  Problem 1: Micrometer counters ignore transactions                          │
│                                                                              │
│   @Transactional method:                                                     │
│   ┌───────────────┐   ┌──────────────┐   ┌───────────────────────┐           │
│   │ begin         │   │ save()       │   │ counter.increment()   │           │
│   │               │   │ (INSERT)     │   │ 0 → 1  (too early!)   │           │
│   └───────────────┘   └──────────────┘   └───────────────────────┘           │
│            │                    │                    │                       │
│            ▼                    ▼                    ▼                       │
│   rollback happens...    INSERT undone      counter STAYS at 1               │
│   ──► metric counts an operation that never happened                         │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│  Problem 2: where the log filter sits decides whether user_id resolves       │
│                                                                              │
│  SecurityContextHolderFilter clears the SecurityContext in its own           │
│  finally block. Log the request BEFORE it  → user_id is always null.         │
│  Log the request AFTER it (inner chain done) → context still populated,      │
│  and the final status (401/403/404/201) is already committed.                │
└──────────────────────────────────────────────────────────────────────────────┘
```

Both problems have one shared shape: *telemetry must observe the outcome, not the
attempt*. `AfterCommitMetrics` solves problem 1; the filter position in `SecurityConfig`
solves problem 2.

### 2.2 Core variables

| Variable | Type | Plain-English meaning |
|---|---|---|
| `counter` | `io.micrometer.core.instrument.Counter` | The meter that counts one kind of committed operation; `increment()` adds 1 |
| `TransactionSynchronizationManager.isSynchronizationActive()` | `boolean` | True while code runs inside an open transaction with synchronization registered |
| `TransactionSynchronization` (anonymous impl) | interface | Callback object whose `afterCommit()` runs right after the transaction commits |
| `start` | `long` | `System.nanoTime()` captured before the filter chain runs |
| `durationMs` | `long` | `(end − start) / 1_000_000` — request wall time in milliseconds |
| `authentication` | `Authentication` | The security principal from `SecurityContextHolder`; null or anonymous on unauthenticated requests |
| `userId` | `String` | `authentication.getName()` for a real user, else `null` — never logged for anonymous requests |
| `meterRegistry` | `MeterRegistry` | The Micrometer registry (Prometheus in this app); `counter(name, tagKey, tagValue)` finds or creates a counter |
| `management.tracing.sampling.probability` | `double` | Fraction of requests that get a trace: `1.0` local/test, `0.1` prod |
| `OTLP_ENDPOINT` | `String` | Collector URL; local/test default `http://localhost:4318/v1/traces`, prod requires it |
| `spring.test.metrics.export` | `String` (`"true"`) | Surefire system property that re-enables metrics export in `@SpringBootTest` |
| `scraper-token` | file | Bearer token Prometheus sends; minted by `observability-up.sh`, mode `0600` |
| `DUMMY_HASH` | `String` | BCrypt hash of a random value compared on unknown/inactive accounts to equalize timing |

### 2.3 Key concepts

| Term | Definition |
|---|---|
| After-commit synchronization | A callback registered with Spring's `TransactionSynchronizationManager` that runs only after the transaction commits — the mechanism behind every business counter |
| Rollback | The transaction aborting; Hibernate undoes the writes, and registered synchronizations never fire `afterCommit()` |
| MDC | Mapped Diagnostic Context — thread-local key/values appended to every log line by the current trace |
| Trace ID | 32-hex-char W3C identifier of one request's full span tree; appears as `traceId` (local) or `trace.id` (ECS) |
| Sampling probability | Per-request coin flip for creating spans: `0.1` = 10% of requests traced |
| Scrape | Prometheus pulling `/actuator/prometheus` every 15 s (see `prometheus.yml:2`) |
| Authority | Spring Security granted permission, e.g. `ROLE_USER` or `SCOPE_prometheus`; `hasAuthority` checks it |
| Reserved Prometheus suffix | `_total`, `_created`, `_bucket`, `_info` — the Prometheus client strips these from meter names, so app meters must not end with them |
| ECS JSON | Elastic Common Schema structured log format (`logging.structured.format.console: ecs`), used by test and prod |
| Fail-open | Span export errors are swallowed; telemetry drops silently rather than failing the request |
| Listener-local telemetry | The workflow counter is emitted by the in-process event listener; a crash before the listener runs loses both the row and the count |

---

## 3. Data structures

The feature has no new Java types — the "data structures" are the three artifacts it
produces: meter registrations, the request log line, and the management config tree.

### 3.1 Meter registrations (the 5 call sites)

```java
// shared pattern used by Create/Update/DeleteActivityUseCase and LoginUseCase/WorkflowEntryApplicationService
meterRegistry.counter(
        "app.activities.lifecycle",   // meter name — dots, not underscores (Micrometer convention)
        "action", "created");          // tag key "action", tag value "created" | "updated" | "deleted"
```

| Meter | Tags | When it fires | Call site |
|---|---|---|---|
| `app.activities.lifecycle` | `action=created` | after the insert commits | `CreateActivityUseCase.java:50-51` |
| `app.activities.lifecycle` | `action=updated` | after the update commits | `UpdateActivityUseCase.java:53-54` |
| `app.activities.lifecycle` | `action=deleted` | after the delete commits | `DeleteActivityUseCase.java:38-39` |
| `app.auth.logins` | `outcome=success` | after the token pair is persisted | `LoginUseCase.java:89-90` |
| `app.auth.logins` | `outcome=failure` | immediately, on `InvalidCredentialsException` | `LoginUseCase.java:64` |
| `app.workflow.entries` | — | after the workflow row persists | `WorkflowEntryApplicationService.java:65` |

Name rule (why `app.workflow.entries`, not `app.workflow.entries.created`):
`WorkflowEntryApplicationService.java:63-65` documents it, `docs/observability.md:30-33`
explains it — the Prometheus client strips reserved suffixes (`_total`, `_created`,
`_bucket`, `_info`) from exported names, so `app.workflow.entries.created` would silently
export as `app_workflow_entries_total` and could collide with the real counter.

### 3.2 The request log line (key-value schema)

```java
log.atInfo()                              // RequestLoggingFilter.java:66-83
        .addKeyValue("http.request.method", String)   // HTTP verb, e.g. "POST" (ECS-aligned)
        .addKeyValue("url.path", String)              // request.getRequestURI() — NO query string, no headers, no body
        .addKeyValue("http.response.status_code", Integer) // final committed status: 200/201/401/403/404/...
        .addKeyValue("duration_ms", Long)             // (end − start) / 1_000_000
        .addKeyValue("user.id", String)               // authenticated subject, or null for anonymous
        .addKeyValue("event.outcome", String)         // "success" (status < 400) | "failure" (status ≥ 400 or unhandled exception)
        .addKeyValue("error.type", String)            // simple class name, ONLY when an exception propagated; absent otherwise
        .log("request completed");
```

MDC enrichment is automatic: `traceId`/`spanId` (local human pattern) or `trace.id`/
`span.id` (ECS JSON in test/prod) ride along on the same line. The field contract is pinned
by `RequestLoggingFilterTest` (unit) and the request-log assertions in
`ObservabilityIntegrationTest`.

### 3.3 Management config tree

```yaml
management:                                  # application.yml:26-55
  endpoints.web.exposure.include: [health, info, prometheus]   # only these 3 are reachable
  endpoint.health.probes.enabled: true       # /actuator/health/liveness + /readiness
  tracing.sampling.probability: 1.0          # local/test; prod overrides to 0.1
  opentelemetry.tracing.export.otlp.endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}
  info:
    git.enabled: true                        # git-commit-id-maven-plugin → git.properties
    build.enabled: true                      # spring-boot-maven-plugin build-info → build.properties
    java.enabled: true                       # JVM version
    env.enabled: false                       # intentionally OFF: /actuator/info is public
```

---

## 4. Algorithm diagrams

### 4.1 `AfterCommitMetrics.incrementAfterCommit` — the branch

```
input: Counter counter
        │
        ▼
   isSynchronizationActive() ?            AfterCommitMetrics.java:23
   ├── YES ──► registerSynchronization(  AfterCommitMetrics.java:24
   │              TransactionSynchronization {
   │                afterCommit() → counter.increment()   ← deferred
   │              })
   └── NO  ──► counter.increment()        AfterCommitMetrics.java:31  ← immediate
```

Timeline comparison — commit vs rollback, concrete values:

```
Commit path (counter starts at 0):
  begin tx → INSERT activities row → publish ActivityCreated → register sync
  → COMMIT → afterCommit() → app.activities.lifecycle{action=created} = 1   ✓

Rollback path (counter starts at 0):
  begin tx → SELECT (activity missing) → ActivityNotFoundException → ROLLBACK
  → no afterCommit callback → app.activities.lifecycle{action=deleted} stays 0   ✓
  (proven by ObservabilityIntegrationTest.rollbackDoesNotIncrementCounter:
   DELETE of a random UUID → 404 → counter unchanged)

Both after-commit branches are also exercised directly against a real transaction:
  ObservabilityIntegrationTest.afterCommitSynchronizationIncrementsOnCommit
  ObservabilityIntegrationTest.afterCommitSynchronizationSkipsIncrementOnRollback
  (PlatformTransactionManager begin/commit/rollback + AfterCommitMetrics; the
  immediate-increment branch is covered by the no-transaction unit tests)
```

### 4.2 `RequestLoggingFilter` — position and timing

```
Registration (SecurityConfig.java:57):
  .addFilterAfter(new RequestLoggingFilter(), SecurityContextHolderFilter.class)

Chain order:
  ┌─────────────────────┐   ┌───────────────────────┐   ┌───────────────────────────────┐
  │ SecurityContext     │ → │ RequestLoggingFilter  │ → │ JWT auth + authorization +    │
  │ HolderFilter        │   │ (measures, logs in    │   │ exception translation         │
  │                     │   │  finally)             │   │ (commits final status)        │
  └─────────────────────┘   └───────────────────────┘   └───────────────────────────────┘
                                                             │
                              finally block: context still   │ 401/403/200/201/404 set
                              populated → user_id resolves   ▼
                                                       response out
```

Timing formula:

```
input: start = System.nanoTime() = 53_408_900_000 ns      (RequestLoggingFilter.java:45)
       end   = System.nanoTime() = 53_421_345_678 ns      (after chain returns, line 49)
formula: durationMs = (end − start) / 1_000_000           (line 49)
output:  12_445_678 / 1_000_000 = 12 ms                   (integer division)
```

`shouldNotFilter` guard (`RequestLoggingFilter.java:37-40`): requests whose URI starts
with `/actuator/health` skip the filter entirely — liveness/readiness probes would spam
one line per scrape.

### 4.3 `/actuator/prometheus` authorization — the decision tree

```
input: GET /actuator/prometheus + Authorization header?
        │
        ├── no token ───────────────────────────► 401  (RestAuthenticationEntryPoint)
        │                                          (test lines 124-125)
        ├── token, no scope claim ───────────────► 403  (RestAccessDeniedHandler)
        │   (e.g. app-issued token: only ROLE_USER)    (test lines 127-130)
        └── token, scope = "prometheus" ─────────► 200  (hasAuthority("SCOPE_prometheus"))
                                                     (test lines 132-135)
```

Authority derivation formula:

```
input: JWT claims {sub, role?, scope?}
  step 1: scope claim "prometheus"  → authority "SCOPE_prometheus"
          (default JwtGrantedAuthoritiesConverter, RoleJwtAuthenticationConverter.java:34-35)
  step 2: role claim "USER"/"ADMIN"  → authority "ROLE_USER"/"ROLE_ADMIN"
          (allow-list KNOWN_ROLES, RoleJwtAuthenticationConverter.java:28,40-41)
  step 3: app-issued tokens carry role but NO scope (JwtTokenService.java:90-97)
  output: app user  → {ROLE_USER}             → 403 on the scrape endpoint
          scraper   → {SCOPE_prometheus}      → 200 on the scrape endpoint
          scraper   → {SCOPE_prometheus}      → 403 on every role-gated business endpoint
                                                (test lines 138-165, least-privilege proof)
```

### 4.4 Meter name transformation (Micrometer → Prometheus)

```
input:  meterRegistry.counter("app.activities.lifecycle", "action", "created")
  step 1: dots → underscores:  app.activities.lifecycle  →  app_activities_lifecycle
  step 2: counters get the _total suffix on export
  step 3: tags → {action="created"}
output: scrape line:
  app_activities_lifecycle_total{action="created"} 1
  (asserted by test line 173)
```

### 4.5 Tracing pipeline

```
input: one HTTP request (sampled)
  → Micrometer Tracing bridge-otel creates a span (W3C traceparent)
  → span IDs written to the MDC: traceId/spanId (local), trace.id/span.id (ECS)
  → log line emitted (section 4.2) carries the IDs → log-trace correlation
  → OTLP exporter → endpoint
       local/test: http://localhost:4318/v1/traces  (application.yml:46)
                   collector → OTLP gRPC → jaeger:4317  (otelcol-config.yml:19-23)
       prod:       ${OTLP_ENDPOINT} — required, FAIL-FAST if unset (application-prod.yml)
                   (or standard OTEL_EXPORTER_OTLP_ENDPOINT; headers via
                   OTEL_EXPORTER_OTLP_HEADERS — both mapped by Boot 4.1 at startup)
  sampling: 1.0 (application.yml:37-38, test), 0.1 default prod (application-prod.yml:43-46)
```

### 4.6 Mode comparison — login success vs failure counting

| | Success | Failure |
|---|---|---|
| When counted | after commit (`AfterCommitMetrics`) | immediately (plain `counter.increment()`) |
| Rationale | tokens must be persisted first; rollback = no login | the failed attempt happened regardless of tx outcome |
| Rollback behavior | count dropped (sync never fires) | count kept (intended) |
| Code | `LoginUseCase.java:89-90` | `LoginUseCase.java:62-66` |
| Oversized password (`InvalidUserException`) | counted as **neither** — it is not an `InvalidCredentialsException` | `LoginUseCase.java:70` throws before any counting |

Profile comparison (logging + tracing):

| Profile | Log format | Sampling | SQL logging |
|---|---|---|---|
| `local` | human-readable, `[traceId,spanId]` (standard pattern, `application.yml`) | 1.0 | DEBUG `org.hibernate.SQL` + TRACE `bind` + `format_sql` (`application-local.yml:33-41`, `8-12`) |
| `test` | ECS JSON | 1.0 | off by default (`application-test.yml:22-30`) |
| `prod` | ECS JSON | 0.1 (`TRACING_SAMPLING_PROBABILITY`) | never (predicates carry user data) |

---

## 5. Event lifecycle — one full operation with concrete values

`POST /api/v1/activities` by a real user, observed end to end.

**Setup values (kept identical everywhere in this document):**
user id `8f1c2e4a-0000-0000-0000-000000000001`, role `USER`; activity id
`a3b1c9d2-1111-2222-3333-444455556666`, name `"Retro 1"`; counters start at 0;
`start` snapshot `53_408_900_000` ns.

```
Step  User / Client                  Application                                  DB           Collectors
────  ─────────────────────────────  ───────────────────────────────────────────  ───────────  ──────────────────────
 1    curl -X POST                   (no processing yet)
      /api/v1/activities
      Bearer <HS256 JWT, sub=8f1c2e4a-…, role=USER>
 2                                  RequestLoggingFilter: start = 53_408_900_000 ns
                                     (RequestLoggingFilter.java:45)
 3                                  RoleJwtAuthenticationConverter: scope? none;
                                     role USER → ROLE_USER (converter:40-41)
 4                                  SecurityConfig: POST /api/v1/activities/**
                                     hasAnyRole(USER, ADMIN) → pass (SecurityConfig.java:68)
 5                                  ActivityController.create → CreateActivityUseCase
                                     (ActivityController.java:58-60)
 6                                  @Transactional begin (CreateActivityUseCase.java:41)
                                     userLookup.findById(8f1c2e4a-…)  ───────────► SELECT users WHERE id=…
                                     Activity.create("Retro 1", null, 8f1c2e4a-…)
                                     activityRepository.save ────────────────────► INSERT activities (id=a3b1c9d2-…)
                                     publishEvent(ActivityCreated)   (line 49)
                                     AfterCommitMetrics: sync registered  (lines 50-51, 24-29)
 7                                  COMMIT ──► afterCommit() fires
                                     app.activities.lifecycle{action=created} 0 → 1
 8                                  WorkflowEventListener (AFTER_COMMIT, REQUIRES_NEW,
                                     WorkflowEventListener.java:30-34)
                                     WorkflowEntryApplicationService.onActivityCreated
                                     save(WorkflowEntry.forActivity) ──────────► INSERT workflow_entries
                                     countCreated() → app.workflow.entries 0 → 1
                                     (service lines 36-42, 63-66; no-op on duplicate, line 37-39)
 9                                  finally block (RequestLoggingFilter.java:66-83):
                                      durationMs = (53_421_345_678 − 53_408_900_000)/1e6 = 12
                                      log.atInfo(): http.request.method=POST,
                                      url.path=/api/v1/activities,
                                      http.response.status_code=201, duration_ms=12,
                                      user.id=8f1c2e4a-…, event.outcome=success,
                                      trace.id=4bf92f3577b34da6a3ce929d0e0e4736
10                                  Response: 201 + Location /api/v1/activities/a3b1c9d2-…
11                                                                                          Prometheus scrape (15 s):
                                                                                            app_activities_lifecycle_total{action="created"} 1
                                                                                            app_workflow_entries_total 1
                                                                                            hikaricp_connections_active 1 (during step 6)
12                                                                                          OTLP exporter → :4318 → collector → jaeger:
                                                                                            one span service=modular-monolith
```

ECS log line actually produced at step 9 (test profile, `application-test.yml:22-25`):

```json
{"@timestamp":"2026-08-19T10:15:30.123Z","log.level":"INFO","message":"request completed",
 "method":"POST","path":"/api/v1/activities","status":201,"duration_ms":12,
 "user_id":"8f1c2e4a-0000-0000-0000-000000000001",
 "trace.id":"4bf92f3577b34da6a3ce929d0e0e4736","span.id":"e19a6f48f5f9a4f3"}
```

---

## 6. Full-stack flow — horizontal swimlane

```
 User            Security chain                  Controller/UseCase         DB             After-commit          Workflow listener       Observability stack
─────────       ──────────────────────          ────────────────────      ──────────      ─────────────────     ──────────────────      ─────────────────────────
 curl POST   │ SecurityContext       │         │ ActivityController   │  SELECT users  │ sync registered     │ @ApplicationModule     │ Prometheus
 Bearer JWT  │ HolderFilter          │         │ → CreateActivity      │  INSERT act.  │ (ACM.java:24)       │ Listener (listener:30) │  │
      │      │ → RequestLogging      │         │   UseCase             │  INSERT wf.   │       │              │ → REQUIRES_NEW tx      │  ├─ scrape :9090
      │      │   Filter (timer on,   │         │   (usecase:41-52)     │      │        │ COMMIT → after-     │ → save wf row          │  │   every 15 s
      │      │   ACM import)         │         │     │                 │      │        │   commit → counter   │ → countCreated()       │  │   (prometheus.yml:2)
      ▼      │ → JWT authz           │         │     ▼                 │      ▼        │   +1 (0→1)           │ → counter +1 (0→1)     │  │   bearer scraper-token
      │      │   (converter:34-44)   │         │  meterRegistry        │   tx commit    │       │              │       │               │  │
      │      │ → authorize matcher   │         │   .counter(...)       │               │       │              │       │               │  ├─ Jaeger UI :16686
      │      │   (SecurityConfig:68) │         │   (usecase:50-51)     │               │       ▼              │       ▼               │  │   spans via
      │      │     │                 │         │     │                 │               │  app_activities_    │  app_workflow_         │  │   collector :4318
      │      │     ▼                 │         │     ▼                 │               │  lifecycle{created}  │  entries_total 1       │  │   (otelcol:19-23)
      │      │  controller executes  │         │  response 201         │               │  total 1             │                       │  ├─ Grafana :3000
      │      │     │                 │         │      │               │               │                      │                       │  │   dashboard
      │      │  finally: log line    │◄────────┘      │               │               │                      │                       │  └─ (dashboards/
      │      │  (status=201,         │                │               │               │                      │                       │      modular-monolith.json)
      │      │  user_id=8f1c2e4a-…)  │                ▼               │               │                      │                       │
      ▼      │            │          │         201 + Location         │               │                      │                       │
 response   │            ▼          │───────────────────────────────►│               │                      │                       │
 201        │   log to stdout/file (ECS JSON, trace.id attached)
```

---

## 7. Design decisions

### 7.1 After-commit increments instead of immediate increments

`AfterCommitMetrics` exists because Micrometer counters are not transactional
(`AfterCommitMetrics.java:10-12`).

- **What breaks without it**: a rolled-back transaction leaves a phantom count. The
  DELETE-404 test proves the fix (`ObservabilityIntegrationTest.java:248-263`).
- Alternative rejected: increment-then-decrement on rollback — racy and unprovable.

### 7.2 The filter sits after `SecurityContextHolderFilter`, not before

Registered at `SecurityConfig.java:57` with a comment chain explaining the trap
(`SecurityConfig.java:50-56`).

- **What breaks without it**: `SecurityContextHolderFilter` clears the context in its own
  `finally`; a filter before it reads `user_id = null` on every request.
- Bonus: the inner chain commits the final status (401/403 included) before the log line —
  the status is truthful, as asserted by `ObservabilityIntegrationTest.java:311-336`.

### 7.3 `RequestLoggingFilter` is not a Spring bean

It is `new`-ed inline in `SecurityConfig` (`SecurityConfig.java:57`), documented at
`RequestLoggingFilter.java:29-31`.

- **What breaks without it**: Boot auto-registers any `OncePerRequestFilter` bean as a
  servlet filter *in addition to* the chain position — every request logs twice.

### 7.4 Scope-only scraper token for Prometheus

`/actuator/prometheus` requires `SCOPE_prometheus` (`SecurityConfig.java:63`); app-issued
tokens never carry a scope claim (`JwtTokenService.java:90-97`), so the only valid scraper
is a dedicated token: `observability-up.sh:17-22` mints the local one with `--role NONE
--scope prometheus`; prod mints one with the app's RSA private key via
`scripts/mint-rsa-jwt.py` (same claim shape, see `docs/observability.md` "Prometheus access").

- **What breaks without it**: metrics (user ids, endpoint names) leak to any authenticated
  user; the test pins the full matrix 401/403/200 (`ObservabilityIntegrationTest.java:122-165`).

### 7.5 ECS JSON in test/prod, human-readable in local

`application-test.yml:22-25` and `application-prod.yml:33-36` set
`logging.structured.format.console: ecs`; the human-readable console pattern (with
`[traceId,spanId]`) is the standard default in `application.yml`, and ECS structured output
overrides it entirely in test/prod.

- **What breaks without it**: prod logs are hard for a log pipeline to parse; local JSON
  is painful for developers. The split is deliberate per profile.

### 7.6 The `env` info contributor is disabled

`application.yml:54-55` — `/actuator/info` is public, so environment details must not
leak; only git/build/java are enabled. Test asserts `$.env` does not exist
(`ObservabilityIntegrationTest.java:273`).

### 7.7 Meter names avoid reserved Prometheus suffixes

`WorkflowEntryApplicationService.java:63-65` + `docs/observability.md:30-33`.

- **What breaks without it**: the exported name silently changes
  (`app.workflow.entries.created` → `app_workflow_entries_total`), breaking dashboards and
  potentially colliding with the real counter at registration time.

### 7.8 Fail-fast production tracing

Local/test default to `localhost:4318` (`application.yml:68`) and fail open by design — no
collector, export warnings, MDC correlation still works. **Prod is the opposite, by design:
fail-fast.** `application-prod.yml` requires `OTLP_ENDPOINT` (no default), so startup
refuses to continue when it is missing (or when the standard
`OTEL_EXPORTER_OTLP_ENDPOINT`/`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` environment variables,
which Spring Boot 4.1 maps to the same property, are absent). Telemetry must be deliberately
configured in production; silently dropped spans are a monitoring failure, not an acceptable
mode. Contract proven by `ProdTelemetryConfigTest.prodProfileFailsFastWithoutOtlpEndpoint`.

### 7.9 The workflow counter is listener-local

`WorkflowEntryApplicationService.java:63-66` counts after the listener's own commit — but
the listener is synchronous and non-durable. A crash between the activity commit and the
listener loses both the workflow row and the count (documented in `docs/observability.md:38-40`).
This is accepted because in-process events are exactly-once-by-construction today
(`WorkflowEventListener.java:14-17`).

---

## 8. Edge cases

| Scenario | How handled | Source |
|---|---|---|
| Transaction rolls back after a counter was "incremented" | Increment deferred; `afterCommit()` never fires; count unchanged | `AfterCommitMetrics.java:23-29`; `afterCommitSynchronizationSkipsIncrementOnRollback` + API 404 test |
| No transaction active (unit tests, non-tx paths) | Immediate increment — nothing to roll back | `AfterCommitMetrics.java:30-32` |
| Chain throws an exception | Logging is in `finally` — the line still appears, exception captured for `error.type` + `event.outcome=failure`, always rethrown | `RequestLoggingFilter.java:60-83`; test `unhandledExceptionIsCapturedForErrorTypeAndRethrown` |
| Anonymous request (no/invalid token) | `user.id = null`; status 401 logged, `event.outcome=failure` | `RequestLoggingFilter.java:70-79`; tests `anonymousRequestLogsNullUserId` + `requestLogCoversRejectedRequests` |
| Health probes (liveness/readiness) | Filter skips `/actuator/health*` entirely | `RequestLoggingFilter.java:37-40`; test `requestLogExcludesQueryStringAndHealthProbes` |
| Query string carries secrets (`?secret=value`) | `getRequestURI()` — path only, query never logged | `RequestLoggingFilter.java:57`; test `requestLogExcludesQueryStringAndHealthProbes` |
| Scraper token used on business endpoints | Only `SCOPE_prometheus` — role-gated endpoints 403 | `SecurityConfig.java:67-74`; test `scraperTokenIsLeastPrivilege` |
| Duplicate event delivery | `findByActivityId(...).isPresent()` → return before save/count | `WorkflowEntryApplicationService.java:37-39` |
| Update arrives before create (out-of-order) | Reconstruct the entry, count it as created | `WorkflowEntryApplicationService.java:45-55` |
| Failed login inside a tx that rolls back | Failure counted immediately — the attempt happened | `LoginUseCase.java:62-66` |
| Password over the BCrypt limit (72 bytes) | `InvalidUserException` — counted as neither success nor failure | `LoginUseCase.java:70`; `docs/observability.md:35-36` |
| No collector running (local/test) | Export warnings; MDC correlation still works | `application.yml:64-68`; `docs/observability.md` "Tracing" |
| Prod without `OTLP_ENDPOINT` | Fail-fast: startup refuses to continue | `application-prod.yml`; `ProdTelemetryConfigTest.prodProfileFailsFastWithoutOtlpEndpoint` |
| Not a git checkout | `failOnNoGitDirectory=false` — `/actuator/info` simply lacks git data | `pom.xml:228-230` |
| Boot 4 test infra disables metrics export | Surefire sets `spring.test.metrics.export=true` | `pom.xml:203-211` |
| Meter name ends in a reserved suffix | Avoided by naming convention; documented in code comment | `WorkflowEntryApplicationService.java:63-65` |

---

## 9. Integration points — the actual call sites

### 9.1 `CreateActivityUseCase.java:49-52` (identical shape in Update/Delete)

```java
eventPublisher.publishEvent(new ActivityCreated(saved.id().value(), saved.name()));
//  ^-- Modulith event; consumed AFTER commit by WorkflowEventListener (listener:30-34)

AfterCommitMetrics.incrementAfterCommit(
        //  ^-- static helper in com.example.app.shared — imported at usecase:8
        meterRegistry.counter("app.activities.lifecycle", "action", "created"));
        //  ^-- MeterRegistry injected constructor bean (usecase:31-38)
        //      name: dots, tag key "action", tag value "created"
```

Each argument, in words:
- `meterRegistry` — the Micrometer registry (Prometheus-backed); injected by Spring.
- `"app.activities.lifecycle"` — meter name; must not end in a reserved suffix.
- `"action"` / `"created"` — tag pair that splits the series (created/updated/deleted).
- `incrementAfterCommit` — defers `counter.increment()` to the commit callback
  (`AfterCommitMetrics.java:24-29`) or fires immediately outside a tx (line 31).

### 9.2 `LoginUseCase.java:62-66` and `:89-90` — the split semantics

```java
} catch (InvalidCredentialsException e) {
    // Failed login attempt - counted immediately (no committed state to wait for).
    meterRegistry.counter("app.auth.logins", "outcome", "failure").increment();
    //  ^-- plain increment: failures are real regardless of tx outcome (line 64)
    throw e;
}
...
// Success is only counted after the transaction commits (tokens persisted).
AfterCommitMetrics.incrementAfterCommit(
        meterRegistry.counter("app.auth.logins", "outcome", "success"));
//  ^-- deferred: if the token pair insert rolls back, no success is counted (lines 89-90)
```

### 9.3 `SecurityConfig.java:57-63` — filter registration + actuator rules

```java
.addFilterAfter(new RequestLoggingFilter(), SecurityContextHolderFilter.class)
//  ^-- constructed here (not a bean!) to avoid duplicate servlet registration;
//      position AFTER SecurityContextHolderFilter so user_id resolves (lines 50-56)

.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
//  ^-- public probes + build metadata; env contributor disabled (application.yml:54-55)
.requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_prometheus")
//  ^-- scrape endpoint; only a scope-only scraper token passes (observability-up.sh:17-22)
```

---

## 10. File map

```
modular-monolith/
├── src/main/java/com/example/app/
│   ├── shared/
│   │   └── AfterCommitMetrics.java            # after-commit counter helper (the core)
│   ├── security/
│   │   ├── web/
│   │   │   └── RequestLoggingFilter.java      # one structured log line per request
│   │   ├── config/
│   │   │   └── SecurityConfig.java            # filter position + actuator authz rules
│   │   └── jwt/
│   │       ├── RoleJwtAuthenticationConverter.java  # scope→SCOPE_*, role→ROLE_* mapping
│   │       └── JwtTokenService.java           # app tokens: role claim, never scope
│   ├── activity/application/usecase/
│   │   ├── CreateActivityUseCase.java         # counter{action=created} after commit
│   │   ├── UpdateActivityUseCase.java         # counter{action=updated} after commit
│   │   └── DeleteActivityUseCase.java         # counter{action=deleted} after commit
│   ├── activity/api/ActivityController.java   # POST /activities entry point
│   ├── user/application/usecase/
│   │   └── LoginUseCase.java                  # app.auth.logins success/failure split
│   └── workflow/application/listener/
│       ├── WorkflowEventListener.java         # @ApplicationModuleListener entry points
│       └── WorkflowEntryApplicationService.java  # app.workflow.entries + idempotency
├── src/main/resources/
│   ├── application.yml                        # management/tracing/jdbc/info config
│   ├── application-local.yml                  # human logs, DEBUG SQL, format_sql
│   ├── application-test.yml                   # ECS JSON, sampling 1.0
│   └── application-prod.yml                   # ECS JSON, sampling 0.1, fail-fast OTLP
├── src/test/java/com/example/app/integration/
│   ├── ObservabilityIntegrationTest.java      # 15 tests: meters, logs, endpoints, authz, after-commit
│   ├── ReadinessIntegrationTest.java          # DB outage → readiness 503/DOWN
│   ├── ProdTelemetryConfigTest.java           # OTLP headers binding + fail-fast startup
│   └── SqlSpanIntegrationTest.java            # JDBC query spans: db.* attributes, sanitized, nested
├── src/test/java/com/example/app/security/web/
│   └── RequestLoggingFilterTest.java          # request log contract: fields, outcome, error capture
├── observability/
│   ├── prometheus.yml                         # jobs: app (bearer token) + otel-collector (:8888)
│   ├── otelcol-config.yml                     # OTLP :4318 → batch → jaeger:4317 + self-metrics :8888
│   └── grafana/provisioning/                  # datasource + dashboard + alert-rules.yml
├── scripts/
│   ├── mint-local-jwt.py                      # HS256 token mint (--scope prometheus)
│   ├── mint-rsa-jwt.py                        # RS256 token mint with the prod private key
│   ├── observability-up.sh                    # mints scraper token, starts UI stack
│   └── observability-smoke-test.sh            # end-to-end verification of the whole path
├── docker-compose.yml                         # observability profile: jaeger v2/collector/prometheus/grafana (+ healthchecks, restart)
├── pom.xml                                    # actuator/micrometer/tracing deps (+ datasource-micrometer for JDBC spans) + surefire flag
└── docs/
    ├── observability.md                       # the WHAT doc (stack, meter catalog, ops)
    └── deep-dive-observability.md             # this document
```
