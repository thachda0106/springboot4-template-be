#!/usr/bin/env bash
# End-to-end observability smoke test against the running local stack:
#   1. Prometheus is up and scrapes the app with the REAL scraper bearer token (target UP)
#   2. App metrics are present (HTTP server + HikariCP)
#   3. Collector health endpoint answers
#   4. Collector exporter queue is empty (spans are being delivered to Jaeger)
#   5. Jaeger has stored at least one trace for service=modular-monolith
#
# Prereqs (same as docs/observability.md):
#   - native app running on :8080
#   - stack up: ./scripts/observability-up.sh  (or `docker compose --profile observability up -d`)
#   - at least one API request made so a trace exists
#   - bash, curl, python (git-bash prerequisites)
set -euo pipefail
cd "$(dirname "$0")/.."

TOKEN_FILE=".observability/scraper-token"
PROM="http://localhost:9090"
JAEGER="http://localhost:16686"
COLLECTOR="http://localhost:13133"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
note() { echo "SMOKE OK: $*"; }

[ -f "$TOKEN_FILE" ] || fail "no $TOKEN_FILE - run ./scripts/observability-up.sh first (it mints the scraper token)"

# prom_query <promql> [timeout_seconds] -> response JSON; retries until the result is
# non-empty or the timeout elapses (Prometheus scrapes every 15s; a fresh metric can
# take a couple of intervals to appear).
prom_query() {
  local promql="$1" timeout="${2:-30}" body
  local deadline=$((SECONDS + timeout))
  while :; do
    body=$(curl -sf -H "Authorization: Bearer $(cat "$TOKEN_FILE")" \
      --get --data-urlencode "query=$promql" "$PROM/api/v1/query") \
      || fail "Prometheus query failed: $promql"
    if echo "$body" | python -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)["data"]["result"] else 1)' 2>/dev/null; then
      echo "$body"
      return 0
    fi
    if [ $SECONDS -ge $deadline ]; then
      fail "no result for query within ${timeout}s: $promql"
    fi
    sleep 2
  done
}

# 1. App target UP via the real bearer token (proves authz works end to end).
up_value=$(prom_query 'up{job="modular-monolith"}' | \
  python -c 'import json,sys; print(json.load(sys.stdin)["data"]["result"][0]["value"][1])')
[ "$up_value" = "1" ] || fail "app scrape target not UP (up=$up_value)"
note "app target up=1, scraped with the real scraper token"

# 2. Business/platform metrics present.
prom_query 'http_server_requests_seconds_count' >/dev/null || fail "http_server_requests_seconds_count missing"
prom_query 'hikaricp_connections' >/dev/null || fail "hikaricp_connections missing"
note "HTTP server + HikariCP metrics present"

# 3. Collector health endpoint (process-level readiness).
curl -sf "$COLLECTOR/" >/dev/null || fail "collector health :13133 not reachable"
note "collector health :13133 answers"

# 4. Exporter queue is empty (batches accumulate while the destination is
#    unreachable) and spans have actually been delivered to Jaeger.
queue_size=$(prom_query 'otelcol_exporter_queue_size' | \
  python -c 'import json,sys; d=json.load(sys.stdin)["data"]["result"]; print(d[0]["value"][1] if d else "0")')
[ "$queue_size" = "0" ] || fail "collector exporter queue is backed up (batches=$queue_size) - Jaeger unreachable?"
prom_query 'otelcol_exporter_sent_spans' >/dev/null || fail "otelcol_exporter_sent_spans missing - no spans delivered yet"
note "collector exporter queue empty, spans delivered to Jaeger"

# 5. Jaeger has stored a trace (indexing is eventually consistent, so poll briefly).
trace_count=""
deadline=$((SECONDS + 20))
while :; do
  trace_count=$(curl -sf "$JAEGER/api/traces?service=modular-monolith&limit=1" | \
    python -c 'import json,sys; print(len(json.load(sys.stdin).get("data") or []))') \
    || fail "Jaeger query API not reachable at $JAEGER"
  [ "$trace_count" -gt 0 ] && break
  [ $SECONDS -ge $deadline ] && break
  sleep 2
done
[ "$trace_count" -gt 0 ] || fail "no traces for modular-monolith in Jaeger - make an API request first (see docs/observability.md)"
note "Jaeger holds traces for modular-monolith"

echo
echo "SMOKE PASS: token-authenticated scrape, metrics, collector health, no export failures, traces in Jaeger."
