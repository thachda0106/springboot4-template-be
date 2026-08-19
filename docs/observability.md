# Observability

How this application is observed: metrics, tracing, structured logging, request logging and
build metadata. Everything here is implemented in the `observability` feature..

## Stack

| Concern | Technology |
|---|---|
| Metrics | Spring Boot Actuator + Micrometer, Prometheus registry (`/actuator/prometheus`) |
| Tracing | Micrometer Tracing + OpenTelemetry bridge, OTLP exporter, JDBC query spans (datasource-micrometer) |
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

### Connection pool metrics (HikariCP)

Registered **automatically** by Spring Boot (`DataSourcePoolMetricsAutoConfiguration`) — no
custom meters. The `pool` tag value is the stable `pool-name` from `application.yml`
(`modular-monolith-pool`), so the tag can be pinned in queries; `sum(...)` still guards
against deployments that rename the pool.

| Meter | Meaning |
|---|---|
| `hikaricp_connections` | current pool size (total connections) |
| `hikaricp_connections_active` | connections checked out by in-flight requests |
| `hikaricp_connections_idle` | connections sitting idle in the pool |
| `hikaricp_connections_pending` | threads waiting for a connection |
| `hikaricp_connections_max` / `_min` | configured pool bounds |
| `hikaricp_connections_timeout_total` | connection acquisition timeouts |
| `hikaricp_connections_acquire_seconds_*` | time to acquire a connection (timer) |
| `hikaricp_connections_usage_seconds_*` | time a connection is checked out (timer) |
| `hikaricp_connections_creation_seconds_*` | time to create a connection (timer) |

Useful PromQL (pool name is stable, `sum()` is kept for multi-instance safety):

```
sum(hikaricp_connections_active)
sum(hikaricp_connections_idle)
sum(hikaricp_connections_pending)
sum(hikaricp_connections_max)
increase(hikaricp_connections_timeout_total[5m])
```

## Tracing

- **Propagation**: W3C trace context (Boot default).
- **Service identity**: `service.name` comes from `spring.application.name`
  (`modular-monolith`).
- **Sampling**: `management.tracing.sampling.probability` — `1.0` in `local`/`test`,
  `0.1` (overridable via `TRACING_SAMPLING_PROBABILITY`) in `prod`.
- **Export**: OTLP HTTP/protobuf to `management.opentelemetry.tracing.export.otlp.endpoint`.
  - `local`/`test`: defaults to `http://localhost:4318/v1/traces`. With the observability
    profile **up**, the OTLP Collector listens on `:4318` and forwards spans to Jaeger
    (app → collector → jaeger). With the profile **down** (and always in `test`, which never
    runs a collector) there is no collector → export warnings, but spans still populate the
    MDC so log correlation works.
  - `prod` is **fail-fast by design**: `application-prod.yml` requires `OTLP_ENDPOINT`
    (`endpoint: ${OTLP_ENDPOINT}`, no default), so startup **refuses to continue** when the
    variable is missing — telemetry must be deliberately configured, never silently dropped.
    Use HTTPS. Two supported ways to configure the endpoint:
    - `OTLP_ENDPOINT` (the fail-fast placeholder above), or
    - the standard `OTEL_EXPORTER_OTLP_ENDPOINT` (or signal-specific
      `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`) environment variable — Spring Boot 4.1 maps it
      to the same property at startup and it takes precedence.
  - Collector auth: set `OTEL_EXPORTER_OTLP_HEADERS` (or the signal-specific
    `OTEL_EXPORTER_OTLP_TRACES_HEADERS`) in the standard OTel format, e.g.
    `OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer%20<token>"`. Spring Boot 4.1 maps it
    to `management.opentelemetry.tracing.export.otlp.headers` (W3C header format). There is
    **no custom `OTLP_HEADERS` variable** — do not use it. The mapping is covered by
    `ProdTelemetryConfigTest` and the missing-endpoint fail-fast by
    `ProdTelemetryConfigTest.prodProfileFailsFastWithoutOtlpEndpoint`.
  - Exporter timeout/retry/queue behavior: OpenTelemetry SDK defaults.
- Trace IDs appear in logs automatically: `trace.id`/`span.id` in ECS JSON (prod/test), and
  `[traceId,spanId]` in the human-readable console pattern — the **standard pattern defined
  in `application.yml`** (`[%X{traceId:-},%X{spanId:-}]`); test/prod override it entirely with
  ECS structured logging.

### SQL spans

Every query executed through the `DataSource` is automatically traced as a span nested
inside the request span (and its database session/transaction). Implemented with
`datasource-micrometer` (by the Micrometer maintainers): the Spring Boot module proxies the
`DataSource`, and the OpenTelemetry module maps query observations to OTel database
semantic conventions (v1.39+):

| Span attribute | Example | Notes |
|---|---|---|
| span name | `SELECT activities` | operation + collection, normalized by the SQL analyzer |
| `db.system.name` | `postgresql` | |
| `db.operation.name` | `SELECT` | parsed from the statement (JSqlParser) |
| `db.collection.name` | `activities` | normalized query form |
| `db.query.text` | `select a1_0.id,… from activities a1_0 where a1_0.id = $1` | **sanitized** — literals scrubbed, bind values never included (`jdbc.datasource-proxy.include-parameter-values` stays false; SQL predicates carry user data) |

Config (`application.yml`): `jdbc.includes: QUERY` (OTel covers query execution only —
CONNECTION/FETCH/KEYS interactions are skipped), `jdbc.opentelemetry.spans.enabled: true`,
`jdbc.opentelemetry.metrics.enabled: false` (DB metrics already come from the HikariCP
meters), sanitization on. Proven end-to-end by `SqlSpanIntegrationTest` (captures the
exported spans in-process and asserts the attributes).

Notes:

- Flyway migration queries and `/actuator/health` DB checks run through the same proxied
  datasource, so startup and probes produce their own root spans — small volume, excluded
  from request logs, sampled like everything else.
- The span is a sibling under the request trace via the security observation spans
  (`security filterchain …`); the trace id is what correlates it to the request.

## Logging

| Profile | Format | Trace correlation |
|---|---|---|
| `local` | human-readable (standard pattern, `application.yml`) | `[traceId,spanId]` |
| `test` | ECS JSON (`logging.structured.format.console: ecs`) | `trace.id` field |
| `prod` | ECS JSON | `trace.id` field |

**DB query logging** — only the `local` profile logs SQL: statements at `DEBUG`
(`org.hibernate.SQL`), bind parameter values at `TRACE` (`org.hibernate.orm.jdbc.bind`),
pretty-printed (`hibernate.format_sql`). This is dev tooling: SQL predicates can carry user
data, so it is **never enabled in `test` or `prod`** (prod logs ECS JSON). To disable,
remove the two `logging.level` lines from `application-local.yml`.

## Request logging

`security/web/RequestLoggingFilter` logs **one structured line per request** with the
standard access-log fields (ECS-aligned names, directly consumable by log pipelines):

| Field | Value |
|---|---|
| `http.request.method` | request method (`GET`, `POST`, …) |
| `url.path` | raw request URI **without the query string** |
| `http.response.status_code` | final status (401/403 included) |
| `duration_ms` | processing time in milliseconds |
| `user.id` | authenticated subject (`Authentication.getName()`); null for anonymous |
| `event.outcome` | `success` (status < 400, no exception) or `failure` (status ≥ 400 or an unhandled exception propagated through the filter) |
| `error.type` | simple exception class name when an unhandled exception propagated (e.g. a 500); absent otherwise |

Trace correlation comes from the MDC (`trace.id`/`span.id` in ECS JSON, `[traceId,spanId]`
locally). The message is a constant (`request completed`) — all signal is in the fields.

- Registered **inside the security filter chain, right after `SecurityContextHolderFilter`**:
  the inner chain (JWT auth, authorization, exception translation) has committed the final
  status (401/403 included) by the time the line is logged, while the `SecurityContext` is
  still populated — so `user.id` resolves correctly. (Registered *before*
  `SecurityContextHolderFilter`, its `finally` would clear the context first and `user.id`
  would always be null.) Logging happens in `finally`, so a line is emitted even when the
  chain throws — the exception is captured for `error.type`/`event.outcome` and always
  rethrown.
- `url.path` is the raw request URI **without the query string**.
- `user.id` is the authenticated subject; null for anonymous requests.
- **No headers, cookies, or request/response bodies are ever logged** (login requests carry
  passwords). Exception details are limited to the type name — messages and stack traces are
  not logged here.
- `/actuator/health` (liveness/readiness probes) is excluded to avoid probe noise.
- One line per request is an accepted cost for this application; probes are excluded and the
  production log pipeline/retention is an operations concern. A sampling/level policy for
  high-volume endpoints is a documented future option, not implemented.
- The field contract is pinned by `RequestLoggingFilterTest` (unit) and the request-log
  assertions in `ObservabilityIntegrationTest`.

## Prometheus access

`/actuator/prometheus` requires the **`SCOPE_prometheus`** authority (least privilege):

- Anonymous → 401; any application user (no scope) → 403; scraper token → 200.
- App-issued tokens carry no `scope` claim, so only a **dedicated scraper token** works:
  - local: `python scripts/mint-local-jwt.py --sub <scraper-id> --scope prometheus`
    (HS256, shared local secret).
  - prod: **minted offline by an operator holding the application's RSA private key**
    (`app.security.jwt.private-key`), the same key the app signs access tokens with:
    ```
    python scripts/mint-rsa-jwt.py --sub scraper-1 --scope prometheus --role NONE \
        --key-file <private-key.pem> --verify-with <public-key.pem> --exp-hours 720
    ```
    The token is a normal app-compatible RS256 JWT (same issuer/audience), carrying only
    `scope=prometheus` and **no role claim** → exactly `SCOPE_prometheus`. The private key
    **must never be given to Prometheus or stored in the scrape config** — only the minted
    bearer token is deployed as a secret. Re-run the command and redeploy before expiry to
    rotate (the script prints the token; `--verify-with` proves it signs correctly).
  - An OIDC/IdP service-account token is *not* supported: the production decoder
    (`RsaJwtDecoderConfig`) validates signatures against the application's own public key.
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

- `GET /actuator/health` → UP (public); `/actuator/health/readiness` aggregates
  `readinessState` + `db` (components shown) and returns 503 when the database is down —
  proven by `ReadinessIntegrationTest`
- `GET /actuator/info` → git/build/java (public)
- `GET /actuator/prometheus` → 401 anonymous / 403 user / 200 with a scraper token
- Every request produces one log line with `[traceId,spanId]`

### Local observability UI (Jaeger + Collector + Prometheus + Grafana)

A compose profile brings up a full browser-facing stack for the **native-run** app above
(no application code changes — the app's OTLP default already targets `localhost:4318`, which
the collector publishes). It starts **only** the four observability services; `postgres`/`app`
are never started by it.

**Prereqs:** Docker (compose v2), git-bash (`bash`, `python`, `curl`), and the native app
running and reachable on `:8080` before scraping.

```bash
./scripts/observability-up.sh   # mints a scraper token, starts jaeger+collector+prometheus+grafana
```

The helper:
- mints a **scope-only** scraper token (`--role NONE --scope prometheus`) — exactly
  `SCOPE_prometheus`, no `ROLE_*` authority — into `.observability/scraper-token`
  (gitignored, mode `0600`, 30-day TTL; **re-run the script to rotate**). It passes
  `JWT_LOCAL_SECRET` through, so a custom local secret still yields a valid token.
- starts `docker compose --profile observability up -d prometheus jaeger grafana
  otel-collector` and waits for each to be reachable on its loopback port. It never prints
  the token. The waits are a **one-time startup check only** — run
  `./scripts/observability-smoke-test.sh` afterwards (or periodically) for ongoing
  verification of the whole path (token scrape, metrics, collector health, no export
  failures, traces in Jaeger).

Trace path: `app --OTLP HTTP :4318--> otel-collector --OTLP gRPC jaeger:4317--> jaeger`
(the collector → Jaeger hop is internal to the compose network).

| Service | URL (loopback only) | What to look for |
|---|---|---|
| Jaeger (v2) | http://localhost:16686 | search `service=modular-monolith` → a trace per API request |
| Collector | http://localhost:4318 (OTLP ingest), http://localhost:13133 (health), `otel-collector:8888` (self-metrics, compose network only) | span flow is proven end-to-end in Jaeger; `docker compose --profile observability logs otel-collector` shows DEBUG memory-limiter/health lines (the collector does not log per-batch lines) |
| Prometheus | http://localhost:9090 | `up{job="modular-monolith"}` = 1, `up{job="otel-collector"}` = 1, business counters, request rate, collector exporter failures |
| Grafana | http://localhost:3000 | provisioned `modular-monolith` dashboard (10 panels: counters, request rate, error rates, p95/p99 latency, pool, timeouts, collector exporter health) + alert rules (see below) |

All observability ports bind to `127.0.0.1` only — nothing is exposed on the LAN. Grafana
runs as an anonymous **viewer** for local convenience; Prometheus scrapes with the bearer token
from `.observability/scraper-token`.

**Cleanup:** `docker compose --profile observability down` — telemetry data is ephemeral by
design (no volumes); counters reset on app restart and the workflow counter is listener-local
(in-process, lost on a crash) — see [Metrics](#metrics).

**Full-compose caveat:** this profile targets the native-run workflow. If you run the compose
`app` service instead, set `OTLP_ENDPOINT` and adjust the Prometheus scrape target (the
`app:8080` service DNS) — the job above points at `host.docker.internal:8080`.

**Troubleshooting:** checks are eventually consistent — batch default timeout is 200ms plus
Jaeger's indexing delay, so wait ≥15s (one scrape interval) plus a few seconds for async OTLP
export before looking. To prove the span path, search Jaeger for `service=modular-monolith`:
since Jaeger no longer publishes `:4318`, a trace there can only have arrived via the
collector. Collector issues show up as `docker compose --profile observability logs
otel-collector` startup/error lines, as a failed readiness check on `:13133`, as a Grafana
alert on `otelcol_exporter_send_failed_spans`, or via `./scripts/observability-smoke-test.sh`.
Locally, spans are dropped fail-open if the collector or Jaeger is down, exactly as when no
collector was present (this is the local dev experience only — **prod is fail-fast**, see
[Tracing](#tracing)). `host.docker.internal` requires the `extra_hosts: host-gateway` entry
on Linux (already in `docker-compose.yml`). Port collisions (4318/13133/16686/9090/3000) with
other local tools are possible.

## Alerting (Grafana)

File-provisioned alert rules live in
`observability/grafana/provisioning/alerting/alert-rules.yml` (evaluated every minute against
the provisioned Prometheus datasource; the `modular-monolith` folder is auto-created):

| Rule | PromQL condition | Severity |
|---|---|---|
| App scrape target is down | `up{job="modular-monolith"} == 0` (2m) | critical |
| Collector scrape target is down | `up{job="otel-collector"} == 0` (2m) | critical |
| Elevated HTTP 5xx rate | 5xx ratio > 5% over 5m (low-traffic floor via `clamp_min`) | warning |
| p99 request latency above 2s | `histogram_quantile(0.99, ...) > 2` (5m) | warning |
| HikariCP pool saturation | `sum(hikaricp_connections_pending) > 5` (5m) | warning |
| HikariCP acquisition timeouts | `sum(increase(hikaricp_connections_timeout_total[5m])) > 0` (5m) | warning |
| Collector exporter failures | `sum(otelcol_exporter_queue_size) > 0` (5m) | warning |

**Collector failure visibility:** the `:13133` health endpoint proves only that the process is
running — it does **not** detect a dead Jaeger exporter (pipeline health checking is not
available in the stock collector image: `check_collector_pipeline` is deprecated/broken and
`healthcheckv2` is not shipped in the distribution). Exporter failures are visible through the
collector's **self-metrics** on `otel-collector:8888` (compose network only, scraped by the
`otel-collector` Prometheus job): when the destination is unreachable, batches back up in
`otelcol_exporter_queue_size` and `otelcol_exporter_in_flight_requests` climbs while
`otelcol_exporter_sent_spans` stops growing (verified live against the stack with Jaeger
stopped). The `otelcol_exporter_send_failed_spans` counter is registered only at the detailed
telemetry level in collector 0.159.0, so the queue is the primary signal. The collector image
has no shell/wget, so it gets no in-container healthcheck — a dead collector is caught by the
target-down alert and recovered by `restart: unless-stopped` in `docker-compose.yml`.