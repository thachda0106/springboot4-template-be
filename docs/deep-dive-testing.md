# Deep Dive — Testing Infrastructure (`src/test`)

> Everything you need to understand how this project tests itself: the two
> abstract bases, the six test layers, the Modulith enforcement fixtures, and
> one request traced end-to-end through the real stack.

---

## 1. Overview Table

| Name | Path | Role |
|---|---|---|
| `AbstractIntegrationTest` | `integration/AbstractIntegrationTest.java` | Boots the full app against one real PostgreSQL Testcontainers container |
| `AbstractApiIntegrationTest` | `integration/AbstractApiIntegrationTest.java` | Adds MockMvc/ObjectMapper + HTTP `createUser` helpers on top of the base |
| `ApplicationModularityTests` | `architecture/ApplicationModularityTests.java` | Pins the module graph (5 modules, dependency whitelists, event wiring) via Modulith `verify()` |
| `ModuleViolationDetectionTests` | `architecture/ModuleViolationDetectionTests.java` | Proves Modulith rejects illegal cross-module access, using a fake fixture |
| `ModulithFixtures` | `modulithfixtures/ModulithFixtures.java` | `@Modulithic` marker anchoring module detection on the fixture package |
| `AlphaService` / `AlphaInternal` | `modulithfixtures/alpha/...` | Legal module "alpha" with an internal package |
| `BetaService` | `modulithfixtures/beta/BetaService.java` | **Deliberate** violation: imports alpha's internals |
| 6 domain test classes | `unit/` (25 tests) | Pure domain rules, no Spring, no DB |
| 6 use-case test classes | `application/` (20 tests) | Use cases with Mockito mocks, no Spring context |
| `RequestLoggingFilterTest` | `security/web/RequestLoggingFilterTest.java` | Log-line contract with mock servlets + logback `ListAppender` |
| 11 API test classes | `integration/` (79 tests) | End-to-end REST + security + persistence + observability against the real stack |
| `ActivityPersistenceIntegrationTest` | `persistence/` (4 tests) | Repository contract + optimistic-lock semantics on real PostgreSQL |

141 tests total, all run by surefire in the `test` phase (no failsafe split).

---

## 2. Declarative Knowledge

### The Problem

A modular monolith must be tested at three different truths at once, and each
truth costs different infrastructure:

```
                     WANT: catch as much as possible, as fast as possible
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        ▼                               ▼                               ▼
   DOMAIN TRUTH                 STACK TRUTH                  GRAPH TRUTH
   rules must hold              sql/schema must            modules must not
   with no framework            match reality,            box into each
        │                       security must bite           other
        │                               │                       │
   unit/ + application/      integration/ (Docker)      architecture/
   (instant, JVM only)      (30-60s, real PG)      (instant, bytecode analysis)
        │                               │                       │
        └───────────────┬───────────────┘                       │
                        ▼                                       ▼
              if you ONLY test the fast layers          if you ONLY verify the
              the DB schema can still be wrong     graph, honest behavior (SQL,
              (e.g. optimistic lock, FKs)             auth) stays unproven
```

The project's answer: **five layers, one Maven phase**. No layer is optional —
unit tests prove domain rules, application tests prove orchestration,
architecture tests prove the module boundaries, integration/persistence tests
prove the stack against a real PostgreSQL 17 container.

### Core Variables

| Variable | Type | Plain-English meaning |
|---|---|---|
| `POSTGRES` | `GenericContainer<?>` | The single shared PostgreSQL 17-alpine container for the whole suite |
| `registry` | `DynamicPropertyRegistry` | Spring's hook: tests hand in JDBC values before the context builds |
| `jwtSecret` | `String` | The fixed test HS256 secret (`test-jwt-secret-do-not-use-in-prod`) both minting and validation use |
| `transactionManager` | `PlatformTransactionManager` | Hand of the real Hibernate transaction manager (from the booted context) |
| `tx` | `TransactionTemplate` | Programmatic begin/commit/rollback walls, so one test controls a transaction exactly |
| `REQUEST_LOG_APPENDER` | `ListAppender<ILoggingEvent>` | In-memory buffer for the `RequestLoggingFilter` structured log lines |
| `SQL_LOG_APPENDER` | `ListAppender<ILoggingEvent>` | In-memory buffer for Hibernate SQL and bind-parameter debug lines |
| `meterRegistry` | `MeterRegistry` (`SimpleMeterRegistry` in unit tests) | The Micrometer registry: where `app.activities.lifecycle` counters live |
| `counter` / `deleted` | `Counter` | A named+tagged Micrometer counter, read before/after an action |
| `actor` | `CurrentUser` | Stub of the authenticated user (`CurrentUser.of("user-1")`) |
| `start` | `long` | `System.nanoTime()` mark when a request enters `RequestLoggingFilter` |
| `failure` | `Throwable` | Exception captured if the filter chain throws (null on success) |
| `status` | `int` | HTTP response code at the moment logging runs |
| `userId` | `String` | Authenticated principal name inside the security context, or `null` |
| `event` | `ILoggingEvent` | One structured log record captured by an appender |
| `kv` | `Map<String,Object>` | The decoded key→value pairs of one `event` |
| `activity` | `Activity` | Domain aggregate under test |
| `activityId` | `UUID` | The id of the activity used by the trace workflows |
| `claim` | `JwtClaim` | One JWT body field as placed by the test (`subject`, `role`, `issuer`, ...) |

### Key Concepts

| Term | Definition |
|---|---|
| **Testcontainers** | A library that manages a throwaway Docker container for a test run; the suite uses a `GenericContainer`, not the classic `PostgreSQLContainer` — Testcontainers 2.x dropped per-database classes (`AbstractIntegrationTest.java:26-32`) |
| **`@DynamicPropertySource`** | Static method that feeds Spring property suppliers *before* context startup (e.g. the mapped database port); supersedes values from `application-test.yml` (`AbstractIntegrationTest.java:38-44`) |
| **MockMvc** | Spring test facility that drives a request through the full filter chain and DispatcherServlet in-process — no real port, but real security filters, controllers and serialization |
| **`jwt()` post-processor** | `SecurityMockMvcRequestPostProcessors.jwt(...)` — sticks a ready-made `JwtAuthenticationToken` with explicitly given authorities into the request; it **bypasses** the real `JwtDecoder` |
| **Minted JWT** | A real HS256 `SignedJWT` produced with Nimbus and the test secret — the only way the real validation pipeline is exercised (`JwtValidationIntegrationTest`) |
| **Spring context caching** | Identical bean-combinations result in one cached application context per JVM; the one PostgreSQL container is therefore sufficient |
| **AFTER_COMMIT** | `@ApplicationModuleListener` = transactional-after-commit + `REQUIRES_NEW` + inert `@Async`; listeners run in the publishing thread, only if the transaction committed |
| **`TransactionTemplate`** | Imperative `tx.execute(...)` / `tx.executeWithoutResult(...)` delegates of the injected `transactionManager` |
| **`ListAppender`** | Logback's in-memory appender: attach to a logger, perform the action, then assert on `APPENDER.list` |
| **`ApplicationModules.verify()`** | Modulith's static check of the dependency graph: cycles, module boundary standard, internals access, and the `@ApplicationModule(allowedDependencies=...)` whitelists |
| **`@Modulithic`** | Marker annotation on the package root so Modulith can detect the fixture modules |
| **context caching caveat** | Boot 4's test starters disable Micrometer metrics export — surefire forces `spring.test.metrics.export=true` (`pom.xml:220-228`) |

All test classes are package-private; all assertions are JUnit5 + AssertJ.

---

## 3. Data Structures

```java
// The fixture payload the HTTP helper posts to /api/v1/users (AbstractApiIntegrationTest.java:43-45)
type UserCreatePayload = {
  name:     String,  // display name
  email:    String,  // unique, lowercase-normalized ("<name>-<uuid>@example.com")
  password: String,  // flat text "s3cret-pass" (default) — hashed later by BCrypt (72-byte limit)
  role:     String,  // "USER" or "ADMIN"
}

type JwtClaims = {            // minted by nimbus in JwtValidationIntegrationTest (lines 115-122)
  sub:    JwtClaim,  // subject = user id
  role:   JwtClaim,  // role claim mapped by RoleJwtAuthenticationConverter
  iss:    JwtClaim,  // must equal "modular-monolith" (test profile)
  aud:    JwtClaim,  // must equal "modular-monolith"
  iat:    Date,      // issue time
  exp:    Date,      // expiration (minted 3600s in the future)
}

type ApiErrorBody = {                       // the JSON error contract (400/401/403/404/409)
  code:        String,  // "INVALID_CREDENTIALS", "CREATOR_NOT_FOUND", "CONFLICT", ...
  message:     String,
  timestamp:   String,
  path:        String,
  fieldErrors: FieldError[]|null,          // bean-validation details (name/field/message)
}

type ActivityCreated = {                   // application event (activity domain)
  activityId: UUID,
  name:       String,
}

type WorkflowEntry = {                    // workflow module read model
  activityId:   UUID,
  activityName: String,
  status:       CREATED | UPDATED,        // Deleted entries are row-wise removed -> 404
}
```

Helper signatures (referenced throughout the lifecycle below):

```
createUser(name:String, [password=..., role="USER"]) -> userId:String   // POST /api/v1/users
createActivity(userId:String, name:String)            -> activityId:String
mint(sub, role, secret, expiresAt, [issuer, audience]) -> token:String   // HS256 SignedJWT
scrapePrometheus()                                     -> scrapeText:String
keyValues(event:ILoggingEvent)                        -> Map<String,Object>
```

---

## 4. Algorithm Diagrams

### 4.1 Container Startup (one per JVM)

```
first test class loads (any @SpringBootTest)
   │
   ▼
static <clinit> of AbstractIntegrationTest         [line 34-36]
   │   POSTGRES = new GenericContainer("postgres:17-alpine")   [line 26-32]
   │     .withEnv(POSTGRES_DB=modular_monolith, POSTGRES_USER=postgres, POSTGRES_PASSWORD=postgres)
   │     .withExposedPorts(5432)
   │     .waitingFor(logMessage "database system is ready...×2")   [line 32]
   ├─ POSTGRES.start()    →    pulls/creates the container
   ▼
Spring context for Class X
   │
   ▼
@DynamicPropertySource.databaseProperties(registry)          [line 38-44]
   │   url  = "jdbc:postgresql://{host}:{mappedPort}/modular_monolith"
   │        (e.g. "jdbc:postgresql://localhost:32768/modular_monolith")
   │   user = "postgres",  password = "postgres"
   ▼
Flyway migrates schema → Hibernate validates → test method runs
```

Key formula:
```
visible JDBC URL = "jdbc:postgresql://{host}:{PORT_MAPPED}/modular_monolith"
  input: container port 5432, mapped 32768  →  output: localhost:32768
```

### 4.2 Choosing a test layer (decision tree)

```
what are you proving?
 │
 ├─ a domain rule (trim, status, BCrypt limits, conversion) ... ──► unit/
 ├─ use-case orchestration (mocks, events, meters, tx) ........──► application/
 ├─ module graph (cycles, whitelists, events) ..................──► architecture/
 ├─ log/security contract in isolation (no DB) ................──► security/web
 ├─ HTTP + security + persist incl. error contract ...........──► integration/
 └─ repository contract + optimistic lock on real schema .....──► persistence/
```

### 4.3 `AfterCommitMetrics.incrementAfterCommit` (guarding metric truth)

```
input: Counter object + current thread's sync state
        │
        ▼
  isSynchronizationActive() ?
        │ yes                                        │ no
        ▼                                            ▼
  registerSynchronization         (no tx around unit tests / non-tx path)
  { afterCommit() → counter.increment() }            counter.increment()
        │
        ▼
  output: counter += 1 ONLY after commit (rollback --> 0)
```

Test proof, concrete values (`ObservabilityIntegrationTest.java:255-295`):

```
deleted Counter == 0
  tx1=begin → incrementAfterCommit(deleted) → commit    → count (after) == 1
  tx2=begin → incrementAfterCommit(deleted) → rollback  → count (after) == 1  // no 2
```

### 4.4 Refresh-rotation race (concurrency test)

```
input: 1 refreshToken R, 2 thread pool, CountDownLatch barrier
  post("/api/v1/auth/refresh", {"refreshToken": R}) from both threads
          │
          ▼
  atomic consume serializes: one acquire gets R, the other gets "used" token
  │                       │
  ▼                       ▼
  200 + new token      401 INVALID_REFRESH_TOKEN
          │
          └────────────► assert {200, 401} == [200, 401] (either order)
                        (AuthApiIntegrationTest.java:279-305)
```

### 4.5 Modulith verify() enforcement (two applications)

```
Real app (Application.class)          Fixture (ModulithFixtures.class, location→true)
        │                                     │
        ▼                                     ▼
   verify()                             verify()
        │                                     │
        ▼                                     ▼
 no violations → GREEN             finds the violation in BetaService
                                           ("non-exposed type", "alpha")
        │                                     │
        └── FAIL the build                   └── throw Violations (asserted.)
```

The fixture exists **only** to prove the mechanism is not decorative
(`ModuleViolationDetectionTests.java:24-29`).

### 4.6 Mode comparison

```
                unit/      application/   architecture/    integration/  persistence/
Spring context    ─            ─              ─                ✔            ✔
DB                ─            ─              ─          Testcontainers  ✔
Framework deps    JUnit only   Mockito        Modulith API    MockMvc      beans
Main cost        ~0 ms/class  ~0 ms/class   ~100 ms        15-45 s/class  15-45 s
Failure signal   1 rule       1 behavior    1 graph error   1 end-to-end  1 contract
Typical guard    trims "x "   never() call   avoided incl.  status()      version()
```

---

## 5. Event Lifecycle

A full operation — `WorkflowEventIntegrationTest.creatingAnActivityCreatesAWorkflowEntry`
(`WorkflowEventIntegrationTest.java:49-59`) — with concrete values:

```
step 1  createUser("workflow-alice")
   POST /api/v1/users
   auth: jwt().jwt(subject="system", role="ADMIN") * ROLE_ADMIN        [jwt() trust]
   body: {"name":"workflow-alice",
          "email":"workflow-alice-<uuid>@example.com",
          "password":"s3cret-pass", "role":"USER"}
   → 201; id = extracted from that JSON id field

step 2  createActivity(uid, "Retro")
   POST /api/v1/activities
            auth: jwt().jwt(subject=uid, claim="USER") * ROLE_USER
   SecurityConfig [58-74]: POST /activities/** → hasAnyRole("USER","ADMIN") → pass
   ActivityController.create [57-67]
   CreateActivityUseCase.execute("Retro", null, CurrentUser)   [41-54]
     . tx                           [@Transactional]
     . userApi.findById(uid)        → found (cross-module contract)
     . Activity.create("Retro")     → version=0
     . repo.save → INSERT INTO activities … (version 0)
     . eventPublisher.publishEvent( ActivityCreated(uuid, "Retro") )   ← in tx
     . AfterCommitMetrics.incrementAfterCommit(app.lifecycle created)
     ── COMMIT (let SQL complete, Postgres commit)
   after hold: AFTER_COMMIT
     . WorkflowEventListener.onActivityCreated        [30-34]
     . WorkflowEntryApplicationService.onActivityCreated(uuid,"Retro")
     . new WorkflowEntry repository.save (own REQUIRES_NEW tx)
     . counter app_activities_lifecycle{action="created"} = 1
   → 201 + Location header

step 3  GET /api/v1/workflow-entries/{uid}
            auth: USER
   → 200  body: {"activityName":"Retro","status":"CREATED"}     [asserted at 55-58]
```

The same class proves the after-commit semantics in isolation (`:96-122`): a
publish inside a *rolled-back* tx delivers nothing — the workflow row never
appears.

---

## 6. Full-Stack Flow (one request, horizontal view)

A test `POST /api/v1/activities` (`ActivityApiIntegrationTest.createActivityReturns201WithLocationAndBody`):

```
 TEST        MOCKMVC / JWT             FILTER CHAIN               HTTP + BIZ
 ──────      ────────────────────      ────────────────           ─────────────────────────
 test.cls    post("/api/v1/activities")                           RequestLoggingFilter
             .with(jwt().jwt(subject,   SecurityConfig matchers   "request completed" line:
             role=USER)) + ROLE_USER    → authorized (line 68)    status=201, user.id, success
             body {"name":"Team retro"}  JWT validated by decoder
                │                            │
                ▼                            ▼
            ActivityController.create →  CreateActivityUseCase.execute (tx)
                                           userApi.findById(uid) → Activity.create
                                           activityRepository.save(Activity v0)
                                           publish ActivityCreated   (in tx)
                │                            │
                ▼                            ▼
        ┌───────────────────────────────────────────────────────────────┐
        │ AFTER COMMIT: afterCommit syncs run → WorkflowEventListener  │
        │ → WorkflowEntryApplicationService → WorkflowEntryRepository.  │
        │ save(entry, REQUIRES_NEW)   (idempotent — see edge case 14)     │
        └───────────────────────────────┬───────────────────────────────┘
                                        │
        MockMvc returns → 201 + Location; assertions:
        status 201, header Location exists, jsonPath name/status/version 0
```
- no token → 401 `UNAUTHORIZED` (`RestAuthenticationEntryPoint`)
- token without role → 403 `FORBIDDEN` (`RestAccessDeniedHandler`)
- unknown creator → 404 `CREATOR_NOT_FOUND`
- stale `version` → 409 `CONFLICT`
- blank name → 400 `VALIDATION_ERROR` with `fieldErrors`

---

## 7. Design Decisions

| Decision | Why this | What breaks otherwise |
|---|---|---|
| `GenericContainer` instead of classic `PostgreSQLContainer` | Testcontainers 2.x removed per-db classes (`AbstractIntegrationTest:12-20`) | compile error / depends on abandoned API docs (e.g. `@ServiceConnection` without a postgres factory) |
| Same static container for the entire suite | One container start for the JVM; Spring context caching reuses it across classes | one container start per test class → ~10 min suite; Docker exhaustion under parallel runs |
| `@DynamicPropertySource` instead of `@TestPropertySource` | gets the real mapped port, no port conflicts | port guessing clashes; brittle URLs in yml |
| `jwt()` post-processor in most API tests | test the *role-based* authorization declaratively; decodes are fast, deterministic | real decoder `JwtValidationIntegrationTest` covers signature/exp/iss/aud instead |
| Real minted JWTs only in `JwtValidationIntegrationTest` | the real `NimbusJwtDecoder` + HMAC secret are exercised end-to-end; this is what `jwt()` never touches | (no tests) → green build but auth silently broken for real tokens |
| **logback `ListAppender`** for log assertions | `OutputCaptureExtension` is unreliable here — logger context is a JVM singleton reset by Spring on context setup (`ObservabilityTest:40-41`) | flaky inter-class ordering, missing lines |
| Single surefire `test` phase (no failsafe) | one failure grammar, simplest CI; fast layers run in same command | none |
| surefire forces `spring.test.metrics.export=true` | Boot 4 test starters disable metrics export — `/actuator/prometheus` + counters would be dead while in tests | observability asserts (`activityLifecycleMetricReflectsApiCall`) fail |
| `TransactionTemplate` for event tests | explicit begin/commit/rollback per test; proves AFTER_COMMIT, not just that "an event happened" | `@Transactional` rollback semantics would hide the AFTER_COMMIT contract |
| Fixture package `com.example.modulithfixtures` | violation-check has its own base domain, real `verify()` in `ApplicationModularityTests` remains clean | a wrong violation inside the real base would break ALL verify tests |
| `@Modulithic` marker root | required in Modulith 2.1 for module detection | silent empty module set → verify becomes a no-op |
| package-private test classes | keeps the API surface of test classes minimal | accidental public misuse in sample |
| `tools.jackson.databind` in tests | project uses Jackson 3 (Boot 4); `com.fasterxml.jackson` is history | `ClassNotFoundException`, wrong `ObjectMapper` identity |

---

## 8. Edge Cases Table

| # | Scenario | How handled | Source |
|---|---|---|---|
| 1 | No Docker on the machine | container start fails with `NoClassDefFoundError: Could not initialize class AbstractIntegrationTest`; stay with the non-Docker classes via `-Dtest='...'` | `AbstractIntegrationTest:34-36`; AGENTS.md |
| 2 | Anonymous request to a protected endpoint | 401 with `code=UNAUTHORIZED`, request log records `user.id=null`, outcome `failure` | `ActivityApiIntegrationTest:177-181`; `RequestLoggingFilterTest:102-113` |
| 3 | Authenticated but no role authority | 403 `FORBIDDEN` (no `ROLE_*` from `jwt()`), even if the request body is fine | `ActivityApiIntegrationTest:45-57` |
| 4 | Blank activity name | 400 `VALIDATION_ERROR`, `fieldErrors[0].field="name"` | `ActivityApiIntegrationTest:60-73` |
| 5 | Unknown creator id | 404 `CREATOR_NOT_FOUND` — raised by `userLookup.findById().orElseThrow` | `CreateActivityUseCase:43-44`, `ActivityApiIntegrationTest:76-85` |
| 6 | Unknown activity id | 404 `ACTIVITY_NOT_FOUND` | `GetActivityQuery:26-27`, `ActivityApiIntegrationTest:100-107` |
| 7 | Stale `version` on update | 409 `CONFLICT` (optimistic lock) — tested end-to-end, twice | `ActivityApiIntegrationTest:126-149`, `ActivityPersistenceIntegrationTest:77-96` |
| 8 | Row with legacy `null` password hash | login → uniform 401 `INVALID_CREDENTIALS` (not a 500); fixture: raw `INSERT INTO users (...) without password_hash` | `AuthApiIntegrationTest:120-136` |
| 9 | Inactive user | 401 on login and refresh; fixture flips status via `JdbcTemplate` | `AuthApiIntegrationTest:104-117`, `229-243` |
| 10 | Password > 72 UTF-8 bytes | 400 at the policy layer (BCrypt limit) | `AuthApiIntegrationTest:317-328` |
| 11 | Oversized password field (100 chars) | 400 by `@Size` validation | `AuthApiIntegrationTest:307-315` |
| 12 | Concurrent refresh of the same token | exactly one 200, one 401 — atomic consume | `AuthApiIntegrationTest:279-305` |
| 13 | Rollback of an activity delete | counter `deleted` unchanged — after-commit synchronization only | `ObservabilityIntegrationTest:285-295` |
| 14 | Duplicate delivery of the same event | idempotent upsert: publishing twice → still one entry | `WorkflowEventIntegrationTest:124-134` |
| 15 | Event in a rolled-back tx | stored AFTER_COMMIT: delivery suppressed on rollback | `WorkflowEventIntegrationTest:110-122` |
| 16 | Query string on a logged URL | dropped (`url.path` only) — no `secret=...` leakage | `ObservabilityIntegrationTest:378-390` |
| 17 | Health probes | skipped entirely: `shouldNotFilter` returns true | `RequestLoggingFilter:51-52`; test `ObservabilityIntegrationTest:392-395` |
| 18 | Bad JWT (wrong key / expired / wrong iss / wrong aud / garbage) | 401 at the decoder | `JwtValidationIntegrationTest:52-105` |
| 19 | Scraper token on business endpoints | scope-only token → 403; metrics still public-ish (SCOPE_prometheus) | `ObservabilityIntegrationTest:145-172` |
| 20 | Metrics export disabled by Boot 4 test infra | surefire system property forces export | `pom.xml:220-228` |

---

## 9. Integration Point (copy-paste of the real call site)

The most reused test helper on the API side — every `integration/` test class
relies on it (`AbstractApiIntegrationTest.java:32-50`), called as
`createUser("alice")` or `createUser(name, password, role)`:

```java
protected String createUser(String name, String password, String role) throws Exception {
    String email = name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/users")
                    .with(jwt().jwt(j -> j.subject("system").claim("role", "ADMIN"))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))   // priv'd admin actor
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name": "%s", "email": "%s", "password": "%s", "role": "%s"}
                            """.formatted(name, email, password, role)))
            .andExpect(status().isCreated())          // 201
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.get("id").asText();                   // the new user id
}
```

Per-argument notes: `name` is trimmed by the domain, `email` is forced unique
by the `-<uuid>` suffix (also lowercase-normalized upstream), `password`
defaults to `"s3cret-pass"` in the 1-arg overload, `role` must be `USER` or
`ADMIN`. The minted id is a UUID string, which doubles as the `sub` claim for
subsequent authenticated calls.

The second call site pattern is the *real-token* mint (`JwtValidationIntegrationTest`
`mint()` + a single caller, lines 36-49):

```java
String token = mint(userId, "USER", jwtSecret, Instant.now().plusSeconds(3600));
mockMvc.perform(post("/api/v1/activities")
                .header("Authorization", "Bearer " + token)   // actual HS256 over the wire
```

---

## 10. File Map

```
src/test/java/com/example/app/
├── unit/                            # plain JUnit + AssertJ, no Spring
│   ├── ActivityTest          5      # aggregate trims/rejects/activates
│   ├── UserTest              6      # email normalize, role, legacy hash restore
│   ├── RefreshTokenTest      2      # expiry + revoke idempotence
│   ├── WorkflowEntryTest     2      # status transitions
│   ├── SecurityModeValidatorTest   5 # key-mode mutual exclusion (unit)
│   └── RoleJwtAuthenticationConverterTest 5   # role claim → ROLE_*
├── application/                      # use cases with Mockito
│   ├── CreateActivityUseCaseTest / Delete / Update    # 2+2+3: save+publish, not-found, 409
│   ├── LoginUseCaseTest      6       # success/fail/inactive/legacy/oversized
│   ├── RefreshTokenUseCaseTest 4     # rotate, consume, inactive
│   └── LogoutUseCaseTest     3       # own token / other's token / unknown
├── architecture/
│   ├── ApplicationModularityTests   8  # graph + whitelists + events via verify()
│   └── ModuleViolationDetectionTests  1 # verify() must throw on the fixture
├── integration/
│   ├── AbstractIntegrationTest       # static container + @DynamicPropertySource
│   ├── AbstractApiIntegrationTest    # MockMvc + ObjectMapper + createUser
│   ├── ActivityApiIntegrationTest  11  # CRUD incl. errors (409/403/404)
│   ├── AuthApiIntegrationTest       15  # login/refresh/logout/RBAC/concurrency
│   ├── JwtValidationIntegrationTest  6  # real HS256 decode, negatives
│   ├── UserApiIntegrationTest        6  # /users/me, admin create
│   ├── WorkflowEventIntegrationTest  6  # event → workflow + AFTER_COMMIT proofs
│   ├── SecurityIntegrationTest      11  # public/private matrix, prometheus scope
│   ├── ApiDocsIntegrationTest        3  # swagger + yaml reachable
│   ├── ReadinessIntegrationTest      1  # /health/readiness follows DB
│   ├── ObservabilityIntegrationTest 15  # metrics, log line, info, spans
│   ├── SqlSpanIntegrationTest        2  # sanitized JDBC spans
│   ├── ProdTelemetryConfigTest       2  # OTLP headers / fail-fast
│   └── BootstrapAdminIntegrationTest 1  # first-admin provisioning
├── persistence/
│   └── ActivityPersistenceIntegrationTest 4 # repo round-trip + optimistic lock + delete
└── security/web/
    └── RequestLoggingFilterTest      4  # fields/outcome/error/anonymous (unit, logback)
modulithfixtures/
├── ModulithFixtures.java             # @Modulithic marker root
├── alpha/AlphaService.java           # legal in-module call
├── alpha/internal/AlphaInternal.java # something not exposed
└── beta/BetaService.java             # the deliberate violation

src/main (touchpoints only)
├── com/example/app/Application.java            # @SpringBootApplication, anchor for verify()
├── security/config/SecurityConfig.java        # filter-chain rules invoked by every API test
├── security/web/RequestLoggingFilter.java     # the log contract under test
├── shared/AfterCommitMetrics.java             # the rollback-proof counter logic
└── resources/application-test.yml             # HMAC secret, TTLs, sampling 1.0, ECS logs
pom.xml (test wiring)  surefire spring.test.metrics.export, testcontainers-junit-jupiter
```