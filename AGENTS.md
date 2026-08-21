# AGENTS.md

Spring Boot 4.1.0 (Spring Framework 7) modular monolith: DDD bounded contexts as Spring
Modulith modules, one PostgreSQL DB, OAuth2 Resource Server (JWT). Java 21, Maven.

## Commands

- Full gate: `./mvnw clean verify` — unit + architecture + integration (integration needs Docker).
- Tests: `./mvnw test`. On Windows cmd use `mvnw.cmd`; `./mvnw` works in git-bash here.
- Non-Docker tests only (unit/application/architecture): `./mvnw test -Dtest='<Class1>,<Class2>'`.
- Local run: `docker compose up -d postgres` then `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
- Dev JWT: `python scripts/mint-local-jwt.py --sub <user-id> --scope "activity:read activity:write"` (HS256, local secret).

## Architecture (non-negotiable)

- Modules: `activity`, `workflow`, `user` (bounded contexts) + `security`, `shared`. **No global
  `controller/`/`service/`/`repository/` packages.** Each module: `api/`, `application/`,
  `domain/`, `infrastructure/persistence/`.
- **Standard module layout (DDD + concern grouping) — all future code must follow it:**
  - Business modules (`user`, `activity`, `workflow`): `application/` is **grouped by concern**,
    never flat: `usecase/` (command orchestrators), `query/` (read paths), `port/`
    (application-owned interfaces implemented by adapters), `policy/` (config-driven rules,
    e.g. TTL, BCrypt limits), `factory/` (aggregate/token factories), `bootstrap/`
    (startup provisioning), `config/` (application-layer `@Configuration`), `result/`
    (use-case result records), `listener/` (event consumers + the services they delegate to).
    Use only the groups that apply; a flat `application/` or ad-hoc grouping is a violation.
  - `security` is a technical module, not a DDD context: the module **root is its public API**
    (`CurrentUser`, `CurrentUserProvider`, `SecurityContextCurrentUserProvider`); internals are
    grouped: `jwt/` (issuance + validation — exposed to `user` via `@NamedInterface("jwt")`,
    declared as `security::jwt` in the consumer's `allowedDependencies`), `config/`
    (`SecurityConfig`, `PasswordEncoderConfig`), `web/` (401/403 handlers).
  - `shared` is a technical module, not a DDD context: the module **root is its public API**
    (`ApiError`, `ConflictException`, `RedisCacheConfigurer`, `AfterCommitMetrics`); internals
    are grouped by responsibility: `error/` (`GlobalExceptionHandler`), `web/`
    (`ApiPathPrefixConfig`, `OpenApiConfig` — OpenAPI/Swagger UI docs, JWT bearer scheme),
    `cache/` (`CacheConfig`, `CacheDefaultsConfig`, `FailOpenCacheErrorHandler`).
    No business types.
- Boundaries are **enforced at test time** by `ApplicationModularityTests` (`verify()`) and
  `ModuleViolationDetectionTests`. Breaking a boundary fails the build.
- **Adding a module**: create `com.example.app.<name>/` with `package-info.java`
  `@ApplicationModule(allowedDependencies = {...})`, expose cross-module contracts via root
  package or `@NamedInterface`, **and** add it to the expected-modules assertion in
  `ApplicationModularityTests` (currently exactly: activity, workflow, user, security, shared).
- Module `@RestControllerAdvice` handlers **must** be `@Order(Ordered.HIGHEST_PRECEDENCE)` —
  otherwise the shared `Exception` catch-all wins and business errors become 500s.
- Domain is plain Java (no Spring/JPA/Security annotations). JPA entities + mappers live in
  `infrastructure/persistence`; repository interfaces in `domain/repository`.
- Transactions are owned by the **application/use-case layer** (`@Transactional`), never
  controllers or domain.
- Events: `activity` publishes `ActivityCreated/Updated/Deleted` via `ApplicationEventPublisher`
  inside its tx; `workflow` consumes via `@ApplicationModuleListener` (runs synchronously after
  commit, own `REQUIRES_NEW` tx). Listeners must be **idempotent**. No Kafka/outbox.

## API conventions

- Global prefix `/api/v1` is applied centrally in `shared/web/ApiPathPrefixConfig`
  (`addPathPrefix` for `@RestController` in `com.example.app` packages only — library
  controllers like springdoc's `/v3/api-docs` must keep their framework paths). Controllers
  declare **resource-relative** paths
  (e.g. `@RequestMapping("/activities")`) — do **not** hardcode `/api/v1` in controllers.
  `SecurityConfig` matchers use the full `/api/v1/...` paths.
- Error contract: `ApiError` record (`code/message/timestamp/path/fieldErrors`); 400/401/403/404/409
  all return it. Optimistic-lock conflicts (`@Version`) → 409.

## Security

- OAuth2 Resource Server — validates JWTs, never issues them. `sub` = user id; `scope` →
  `SCOPE_<scope>` authorities.
- local/test: HMAC secret (`app.security.jwt.secret-key`); prod: `JWT_ISSUER_URI` (OIDC).
- Controllers translate the principal to `CurrentUser` via `CurrentUserProvider`; security types
  never reach the domain.

## Persistence

- Flyway owns the schema (`ddl-auto: validate`). New tables need a new `V{n}` migration in
  `src/main/resources/db/migration/`. One DB with cross-module FKs (deliberate).

## Quirks / gotchas

- **Jackson 3**: import `tools.jackson.databind.*`, not `com.fasterxml.jackson.*`.
- **Testcontainers 2.x**: no `PostgreSQLContainer` class — use `GenericContainer` +
  `@DynamicPropertySource` (see `AbstractIntegrationTest`).
- Integration/persistence tests need **Docker**; without it they fail with a confusing
  `NoClassDefFoundError: Could not initialize class AbstractIntegrationTest`.
- Spring Boot 4.1 / Framework 7 / Modulith 2.1.0 — newer APIs than most online docs.
- Flyway pinned to 12.11.0 (PG 16.14/17.10 support).

## Tests

- `unit/` domain tests · `application/` use-case tests (Mockito) · `architecture/` Modulith
  verification (no Docker) · `integration/` + `persistence/` Testcontainers (Docker) ·
  `modulithfixtures/` deliberate violations for the enforcement test.

## Docs

`docs/` is the source of truth for architecture details: `architecture.md`, `module-boundaries.md`,
`event-driven.md`, `transaction-boundaries.md`, `security.md`, `evolution-to-microservices.md`.
