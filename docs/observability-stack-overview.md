# Observability Stack — High-Level Overview

> Living doc: high-level component picture for metrics, traces and logs. When the stack changes,
> update the diagrams below **before** touching `observability.md` / `deep-dive-observability.md`
> (the low-level source of truth).

## 1. Current stack (as implemented)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  THE APP  (Spring Boot 4.1 / Micrometer + OTel bridge)                      │
│                                                                              │
│  ┌─────────────┐   ┌──────────────────┐   ┌───────────────────────────┐      │
│  │  Micrometer │   │ Micrometer       │   │ Logback (JSON, MDC:       │      │
│  │  (metrics)  │   │ Tracing (spans)  │   │ traceId/spanId)           │      │
│  └──────┬──────┘   └────────┬─────────┘   └─────────────┬─────────────┘      │
│         │                   │                            │                    │
│  EXPOSED APIs:                                          │                    │
│  ┌────────────────────────────────────────────────────┐   │                    │
│  │ /actuator/prometheus  ← PULL  (metrics text)       │   │                    │
│  │ /actuator/health      ← PULL  (liveness/readiness) │   │                    │
│  │ /actuator/info        ← PULL  (git/build/java)     │   │                    │
│  └──────────────────────┬─────────────────────────────┘   │                    │
│                         │ OTLP PUSH (HTTP :4318/v1/traces)│ stdout (no API)     │
└─────────────────────────┼────────────────────────────────┼─────────────────────┘
                          │                                │
              ┌───────────┴───────────┐                    │
              ▼                       ▼                    ▼
    ┌─────────────────┐     ┌─────────────────┐   ┌──────────────────┐
    │   PROMETHEUS    │     │ OTEL COLLECTOR  │   │ (NOT in this     │
    │   scrapes :9090  │     │ (pass-through,  │   │  stack — no      │
    │   ↓ app every 15s│     │  batch+limiter) │   │  Loki/ELK; logs  │
    │   (bearer token) │     └────────┬────────┘   │  live in docker  │
    │   ↓ collector    │              │ OTLP gRPC  │  compose logs)   │
    │   self-metrics   │              ▼            └──────────────────┘
    └────────┬────────┘     ┌─────────────────┐
             │              │   JAEGER v2     │   ← push only, never scrapes
             │              │  (UI :16686)    │
             ▼              └────────┬────────┘
    ┌───────────────────────────────────────────────┐
    │                   GRAFANA                     │
    │  datasource: Prometheus (uid: prometheus)     │  ← dashboards + ALERTS
    │  datasource: Jaeger    (uid: jaeger, :16686)  │  ← trace search/waterfalls
    └───────────────────────────────────────────────┘
```

### What the app exports

| Signal | App exports via | Flow | Model |
|---|---|---|---|
| **Metrics** | `GET /actuator/prometheus` (Bearer `SCOPE_prometheus`) | Prometheus **pulls** it every 15s → Grafana queries Prometheus | pull |
| **Traces** | `POST :4318/v1/traces` (OTLP) — app *pushes* spans | app → collector → Jaeger (gRPC :4317) → Grafana queries Jaeger's API | push |
| **Logs** | stdout only (no API) | dead-ends in `docker compose logs` — **the gap** | push |

Key facts:

- Only `health`, `info`, `prometheus` are exposed (`application.yml:48-52`). `health` + `info` are
  public; `prometheus` requires the least-privilege scraper token (see `deep-dive-observability.md` §4.3).
- The OTLP endpoint default is `http://localhost:4318/v1/traces` — no collector present ⇒ export
  warnings, but trace IDs still populate the MDC for log correlation (`application.yml:70-77`).
- The collector is a **traces-only pass-through** (batch + memory_limiter); metrics never touch it
  (Prometheus scrapes the app directly, `otelcol-config.yml:3`). Removing the collector is possible
  today: Jaeger v2 accepts OTLP HTTP on `:4318` natively (see `docker-compose.yml` notes).
- Jaeger **never scrapes** — it only receives pushed spans. Prometheus does all scraping.
- The collector's self-metrics (`otelcol_exporter_queue_size`, `:8888`) are what the
  `otel-collector target-down` Grafana alert is built on (`TASKS.md` D4/D5) — removing the collector
  loses the only "traces silently dropping" signal.

## 2. Industry-standard full stack (OpenTelemetry, 2026)

One SDK in the app emits all three signals in a standard format. Everything flows through the
collector (optional but standard for multi-backend / scale), and Grafana unifies the three backends.
Metrics stay pull-based via Prometheus; traces and logs are push.

```
┌───────────────────────────────────────────────────────────────────────────────┐
│  YOUR APP(S)  — ONE OTel SDK (Micrometer + OTel bridge + OTel log appender)    │
│                                                                                │
│  /actuator/prometheus (pull)    OTLP :4318/v1/traces (push)                    │
│         │                              │                                      │
│         │                              ▼                                      │
│         │                    ┌─────────────────────┐   ┌──────────────────┐   │
│         │                    │  OTEL COLLECTOR     │   │  LOG AGENT       │   │
│         │                    │  batch, memory_lim  │   │  (Alloy /        │   │
│         │                    │  sampling, routing, │   │  Fluent Bit /    │   │
│         │                    │  redaction          │   │  Vector) — reads │   │
│         │                    └─────────┬───────────┘   │  stdout of app   │   │
│         │                              │               └────────┬────────┘   │
└─────────┼──────────────────────────────┼────────────────────────┼────────────┘
          │                              │                        │
          ▼                              ▼                        ▼
  ┌─────────────────┐   ┌──────────────────────┐   ┌─────────────────────┐
  │   PROMETHEUS    │   │   TRACE BACKEND      │   │    LOG BACKEND      │
  │  (metrics DB +  │   │   Tempo (native OTLP │   │    Loki (labels +   │
  │  Alertmanager)  │   │   ingest) — or       │   │    grep) — or ELK   │
  │                 │   │   Jaeger v2          │   │    (full-text)      │
  └────────┬────────┘   └──────────┬───────────┘   └──────────┬──────────┘
           │                       │                          │
           ▼                       ▼                          ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │                       GRAFANA (single UI)                           │
  │  dashboards + alerts (Prometheus) · trace waterfall search (Tempo)  │
  │  log explore (Loki) · derived fields: traceId in logs → "View in    │
  │  Tempo" jump link (log→trace correlation)                           │
  └─────────────────────────────────────────────────────────────────────┘
```

### What the industry stack adds vs. the current one

| Piece | Current | Industry standard | Why |
|---|---|---|---|
| Log pipeline | stdout → dead end | agent (Alloy/Fluent Bit/Vector) → Loki | grep + alert on logs, log→trace correlation via traceId |
| Trace backend | Jaeger v2 (OTLP) | Tempo (or Jaeger v2) | both fine; Tempo is the Grafana-native, Prometheus-scale option |
| Collector | present (traces only) | present (all signals, optional) | decouples backends, sampling, redaction; not strictly needed for a single app |
| Metrics | Prometheus pull | Prometheus pull (OTLP push as alternative) | unchanged — the dominant model |
| Alerting | Grafana alerts | Prometheus + Alertmanager (or Grafana) | Alertmanager for page-relevant, routed, deduplicated alerting |

### Evolution path from current → industry standard

1. **Logs (biggest gap):** make logging JSON everywhere (prod already ECS), run a log agent
   sidecar/container, add Loki, provision a Grafana Loki datasource, map `traceId` to a derived
   field and add the Tempo/Jaeger jump link.
2. **Switch Jaeger → Tempo** (optional): Tempo ingests OTLP natively like Jaeger v2, so it is a
   drop-in with the same collector pipeline — keep Jaeger if it does the job.
3. **Keep the collector** once > 1 app exists; it becomes the single egress point and the place to
   add tail-based sampling. For the single-app modular monolith it is removable (see §1).

## Update log

| Date | Change |
|---|---|
| 2026-08-19 | Created; current-stack diagram + industry-standard full stack. |
