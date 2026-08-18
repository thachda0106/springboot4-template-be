# Observability

How this application is observed: metrics, tracing, structured logging, request logging and
build metadata. Everything here is implemented in the `observability` feature..

## Stack

| Concern | Technology |
|---|---|
| Metrics | Spring Boot Actuator + Micrometer, Prometheus registry (`/actuator/prometheus`) |
| Tracing | Micrometer Tracing + OpenTelemetry bridge, OTLP exporter |
| Logging | Logback; **ECS JSON** in `prod` and `test`, human-readable in `local` |
| Request logging | `security/web/RequestLoggingFilter` (one structured line per request) |
| Build metadata | `/actuator/info` — git commit, build time, Java version |

## Metrics

Business counters are recorded in the **application layer** (never domain) and are
**committed-operation** metrics: the increment is registered as an after-commit
synchronization (`shared/AfterCommitMetrics`), so a rolled-back transaction does not count.

| Meter | Tags | Semantics | Location |
|---|---|---|---|
| `app.activities.lifecycle` | `action=created\|updated\|deleted` | committed aggregate change | `Create/Update/DeleteActivityUseCase` |
| `app.auth.logins` | `outcome=success\|failure` | success = committed login (tokens persisted); failure = failed attempt (counted immediately) | `LoginUseCase` |
| `app.workflow.entries` | — | workflow row actually persisted (both the create path and the out-of-order reconstruction); duplicates/no-ops not counted | `WorkflowEntryApplicationService` |

Notes:

- Meter names must **not end in a reserved Prometheus suffix** (`_total`, `_created`, `_bucket`,
  `_info`): the `prometheus-metrics` client strips it, silently renaming the exported metric
  (e.g. `app.workflow.entries.created` would be exported as `app_workflow_entries_total`) and a
  colliding meter would fail to register. Name meters by what they measure, not by the event.

- `InvalidUserException` (oversized password) is a validation error and counts as **neither**
  success nor failure.
- The workflow counter is **listener-local telemetry**: it is emitted by the synchronous,
  non-durable event listener. A process crash between the activity commit and the listener
  execution loses both the workflow row and the counter — inherent to in-process events
  (see `event-driven.md`).

## Tracing

- **Propagation**: W3C trace context (Boot default).
- **Service identity**: `service.name` comes from `spring.application.name`
  (`modular-monolith`).
- **Sampling**: `management.tracing.sampling.probability` — `1.0` in `local`/`test`,
  `0.1` (overridable via `TRACING_SAMPLING_PROBABILITY`) in `prod`.
- **Export**: OTLP HTTP/protobuf to `management.opentelemetry.tracing.export.otlp.endpoint`.
  - `local`/`test`: defaults to `http://localhost:4318/v1/traces` (no collector → export
    warnings, but spans still populate the MDC so log correlation works).
  - `prod`: **`OTLP_ENDPOINT` is required** (no localhost default). Use HTTPS and, if the
    collector requires auth, set the `headers` map from environment variables. If unset,
    spans are created but **not exported** (fail-open) — telemetry is silently dropped.
  - Exporter timeout/retry/queue behavior: OpenTelemetry SDK defaults.
- Trace IDs appear in logs automatically: `trace.id`/`span.id` in ECS JSON (prod/test), and
  `[traceId-spanId]` in the human-readable local pattern (Boot's default console pattern
  includes `[%X{traceId:-}-%X{spanId:-}]` when tracing is on the classpath; no custom
  `LOG_CORRELATION_PATTERN` is set).

## Logging

| Profile | Format | Trace correlation |
|---|---|---|
| `local` | human-readable (Boot default pattern) | `[traceId-spanId]` |
| `test` | ECS JSON (`logging.structured.format.console: ecs`) | `trace.id` field |
| `prod` | ECS JSON | `trace.id` field |

## Request logging

`security/web/RequestLoggingFilter` logs **one structured line per request**:

```
method, path, status, duration_ms, user_id   (+ trace.id / span.id from the MDC)
```

- Registered **inside the security filter chain, right after `SecurityContextHolderFilter`**:
  the inner chain (JWT auth, authorization, exception translation) has committed the final
  status (401/403 included) by the time the line is logged, while the `SecurityContext` is
  still populated — so `user_id` resolves correctly. (Registered *before*
  `SecurityContextHolderFilter`, its `finally` would clear the context first and `user_id`
  would always be null.) Logging happens in `finally`, so a line is emitted even when the
  chain throws.
- `path` is the raw request URI **without the query string**.
- `user_id` is the authenticated subject (`Authentication.getName()`); absent for anonymous
  requests.
- **No headers, cookies, or request/response bodies are ever logged** (login requests carry
  passwords).
- `/actuator/health` (liveness/readiness probes) is excluded to avoid probe noise.
- One line per request is an accepted cost for this application; probes are excluded and the
  production log pipeline/retention is an operations concern. A sampling/level policy for
  high-volume endpoints is a documented future option, not implemented.

## Prometheus access

`/actuator/prometheus` requires the **`SCOPE_prometheus`** authority (least privilege):

- Anonymous → 401; any application user (no scope) → 403; scraper token → 200.
- App-issued tokens carry no `scope` claim, so only a **dedicated scraper token** works:
  - local: `python scripts/mint-local-jwt.py --sub <scraper-id> --scope prometheus`
  - prod: minted by the external IdP / service account.
- Rationale: metrics leak internals (user ids, endpoint names); scraping without auth should
  be protected by network policy in addition to the token.

## Build metadata (`/actuator/info`)

- `git.commit.id` — from `git-commit-id-maven-plugin` (`git.properties`).
- `build.time` — from the Spring Boot Maven plugin `build-info` goal (`build.properties`).
- `java.version` — from the Java info contributor.
- The **`env` contributor is disabled**: `/actuator/info` is public and must not expose
  environment details.
- `git.properties`/`build.properties` are generated by the Maven lifecycle. Running
  `mvnw.cmd compile spring-boot:run ...` (instead of bare `spring-boot:run`) ensures they
  exist and also enables devtools restart on code changes.

## Local development

```bash
docker compose up -d postgres
mvnw.cmd compile spring-boot:run -Dspring-boot.run.profiles=local
```

Then:

- `GET /actuator/health` → UP (public)
- `GET /actuator/info` → git/build/java (public)
- `GET /actuator/prometheus` → 401 anonymous / 403 user / 200 with a scraper token
- Every request produces one log line with `[traceId-spanId]`