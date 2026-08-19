#!/usr/bin/env bash
# Bring up the local observability UI stack (Jaeger + OpenTelemetry Collector + Prometheus
# + Grafana) for the native-run app (docker compose up -d postgres; mvnw.cmd spring-boot:run ...).
#
# Starts ONLY the four observability services (explicit service list - never the
# compose app/postgres). Mints a scope-only scraper token into .observability/
# (gitignored, mode 0600) for /actuator/prometheus.
set -euo pipefail
cd "$(dirname "$0")/.."

umask 077
mkdir -p .observability

# Scope-only scraper token: SCOPE_prometheus, NO role claim => no ROLE_* authority.
# 30-day TTL - re-run this script to rotate. Passes JWT_LOCAL_SECRET through so a custom
# local secret still yields a valid token.
python scripts/mint-local-jwt.py \
  --sub scraper-1 \
  --scope prometheus \
  --role NONE \
  --secret "${JWT_LOCAL_SECRET:-local-dev-secret-change-me-0123456789abcdef}" \
  --exp-hours 720 > .observability/scraper-token
chmod 600 .observability/scraper-token

docker compose --profile observability up -d prometheus jaeger grafana otel-collector

wait_for() { # bounded host-side readiness (services are reachable via loopback)
  local url="$1" name="$2"
  for _ in $(seq 1 30); do
    if curl -sf "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "ERROR: $name not reachable at $url" >&2
  return 1
}
wait_for http://localhost:9090/-/healthy "Prometheus"
wait_for http://localhost:3000/api/health "Grafana"
wait_for http://localhost:16686/ "Jaeger"
wait_for http://localhost:13133 "Collector"

echo "Observability UI stack up (localhost only):"
echo "  Jaeger      http://localhost:16686"
echo "  Prometheus  http://localhost:9090"
echo "  Grafana     http://localhost:3000"
echo "  Collector   http://localhost:4318 (OTLP ingest), http://localhost:13133 (health)"
echo
echo "The waits above are a one-time startup check only. Ongoing verification:"
echo "  ./scripts/observability-smoke-test.sh"