# Development task runner for the modular monolith.
# Requires git-bash (./mvnw) + Docker. Run `make help` for the full list.

SHELL := /bin/bash

MW      := ./mvnw
COMPOSE := docker compose
PY      := python

# Overridable via `make start:prod DB_URL=...` or env (no change needed for local defaults).
DB_URL      ?= jdbc:postgresql://localhost:5432/modular_monolith
DB_USERNAME ?= postgres
DB_PASSWORD ?= postgres

.PHONY: help start:local start:prod \
        docker:up docker:up:app docker:down docker:logs docker:ps \
        observability:up observability:down observability:smoke \
        test verify build clean token

help: ## Show this help
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

## --- Run the application ---

start:local: docker:up ## Run locally with the local profile (HMAC JWT, devtools)
	$(MW) compile spring-boot:run -Dspring-boot.run.profiles=local

start:prod: docker:up ## Production-like run (prod profile, RSA JWT). Needs JWT_PRIVATE_KEY/JWT_PUBLIC_KEY
	@test -n "$$JWT_PRIVATE_KEY" -a -n "$$JWT_PUBLIC_KEY" \
	  || { echo "ERROR: export JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (RSA PEM) first"; exit 1; }
	$(MW) spring-boot:run -Dspring-boot.run.profiles=prod

## --- Docker ---

docker:up: ## Start the database (postgres) for native local runs
	$(COMPOSE) up -d postgres

docker:up:app: ## Build and start the full stack in containers (app + postgres)
	$(COMPOSE) up --build

docker:down: ## Stop and remove all containers
	$(COMPOSE) down

docker:logs: ## Tail logs of all running services
	$(COMPOSE) logs -f

docker:ps: ## Show container status
	$(COMPOSE) ps

## --- Observability ---

observability:up: ## Start Jaeger/Collector/Prometheus/Grafana + mint the scraper token
	./scripts/observability-up.sh

observability:down: ## Stop the observability stack
	$(COMPOSE) --profile observability down

observability:smoke: ## End-to-end observability smoke test (needs app + stack running)
	./scripts/observability-smoke-test.sh

## --- Build / test ---

test: ## Unit + application + architecture tests (no Docker needed)
	$(MW) test

verify: ## Full gate: clean verify (integration tests need Docker)
	$(MW) clean verify

build: ## Package the application (skips tests)
	$(MW) clean package -DskipTests

clean: ## Remove build output
	$(MW) clean

## --- Dev tools ---

token: ## Mint a local dev JWT: make token SUB=<user-id> [ROLE=ADMIN|USER] [SCOPE="activity:read"]
	$(PY) scripts/mint-local-jwt.py --sub $(SUB) $(if $(ROLE),--role $(ROLE)) $(if $(SCOPE),--scope "$(SCOPE)")
