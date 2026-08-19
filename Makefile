# Development task runner for the modular monolith.
# Requires git-bash (./mvnw) + Docker. Run `make help` for the full list.
# Colons in target names are escaped with `\:` for GNU make portability
# (invoke them normally, e.g. `make start:local`).

MW      := ./mvnw
COMPOSE := docker compose
PY      := python

# Overridable via `make start:prod DB_URL=...` or env (no change needed for local defaults).
DB_URL      ?= jdbc:postgresql://localhost:5432/modular_monolith
DB_USERNAME ?= postgres
DB_PASSWORD ?= postgres

.PHONY: help \
        start\:local start\:prod \
        docker\:up docker\:up\:app docker\:down docker\:logs docker\:ps \
        observability\:up observability\:down observability\:smoke \
        test verify build clean token

## @target help :: Show this help
help:
	@grep -E '^## @target ' $(MAKEFILE_LIST) \
	| sed 's/^## @target //' \
	| awk -F' :: ' '{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

## @target start:local :: Run locally with the local profile (HMAC JWT, devtools)
start\:local: docker\:up
	$(MW) compile spring-boot:run -Dspring-boot.run.profiles=local

## @target start:prod :: Production-like run (prod profile, RSA JWT). Needs JWT_PRIVATE_KEY/JWT_PUBLIC_KEY
start\:prod: docker\:up
	@test -n "$$JWT_PRIVATE_KEY" -a -n "$$JWT_PUBLIC_KEY" \
	  || { echo "ERROR: export JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (RSA PEM) first"; exit 1; }
	$(MW) spring-boot:run -Dspring-boot.run.profiles=prod

## @target docker:up :: Start the database (postgres) for native local runs
docker\:up:
	$(COMPOSE) up -d postgres

## @target docker:up:app :: Build and start the full stack in containers (app + postgres)
docker\:up\:app:
	$(COMPOSE) up --build

## @target docker:down :: Stop and remove all containers
docker\:down:
	$(COMPOSE) down

## @target docker:logs :: Tail logs of all running services
docker\:logs:
	$(COMPOSE) logs -f

## @target docker:ps :: Show container status
docker\:ps:
	$(COMPOSE) ps

## @target observability:up :: Start Jaeger/Collector/Prometheus/Grafana + mint the scraper token
observability\:up:
	./scripts/observability-up.sh

## @target observability:down :: Stop the observability stack
observability\:down:
	$(COMPOSE) --profile observability down

## @target observability:smoke :: End-to-end observability smoke test (needs app + stack running)
observability\:smoke:
	./scripts/observability-smoke-test.sh

## @target test :: Unit + application + architecture tests (no Docker needed)
test:
	$(MW) test

## @target verify :: Full gate: clean verify (integration tests need Docker)
verify:
	$(MW) clean verify

## @target build :: Package the application (skips tests)
build:
	$(MW) clean package -DskipTests

## @target clean :: Remove build output
clean:
	$(MW) clean

## @target token :: Mint a local dev JWT: make token SUB=<user-id> [ROLE=ADMIN|USER] [SCOPE="activity:read"]
token:
	$(PY) scripts/mint-local-jwt.py --sub $(SUB) $(if $(ROLE),--role $(ROLE)) $(if $(SCOPE),--scope "$(SCOPE)")