# Deep Dive — Redis Caching + Distributed Rate Limiting

Deep dive into the staged changes: a Redis-backed read cache (Spring Cache, three bounded contexts) and Redis-backed distributed per-IP rate limiting (security module), with shared fail-open infrastructure. All line numbers refer to the staged working-tree files.

---

## 1. OVERVIEW TABLE

| Name | Path (src/main/java/...) | Role |
|---|---|---|
| `CacheConfig` | `shared/cache/CacheConfig.java` | Builds the single app-wide `RedisCacheManager` from shared defaults + per-cache contributions; installs the fail-open error handler. |
| `CacheDefaultsConfig` | `shared/cache/CacheDefaultsConfig.java` | Shared Redis cache defaults bean: TTL, key prefix, string key serializer, JSON value serializer, null-caching off. |
| `FailOpenCacheErrorHandler` | `shared/cache/FailOpenCacheErrorHandler.java` | Logs and swallows every cache operation error → Redis outage degrades to DB reads, never 500s. |
| `RedisCacheConfigurer` | `shared/RedisCacheConfigurer.java` | Contributor contract so `shared` never imports business types. |
| `ActivityCacheConfiguration` | `activity/infrastructure/cache/ActivityCacheConfiguration.java` | Contributes the `activities` cache (typed serializer + `ActivityCacheMixin`). |
| `ActivityCacheMixin` | `activity/infrastructure/cache/ActivityCacheMixin.java` | Jackson property-shape declaration for the annotation-free `Activity` domain class. |
| `WorkflowEntryCacheConfiguration` | `workflow/infrastructure/cache/WorkflowEntryCacheConfiguration.java` | Contributes the `workflow-entries` cache. |
| `WorkflowEntryCacheMixin` | `workflow/infrastructure/cache/WorkflowEntryCacheMixin.java` | Jackson property-shape declaration for `WorkflowEntry`. |
| `UserSummaryCacheConfiguration` | `user/infrastructure/cache/UserSummaryCacheConfiguration.java` | Contributes the `user-summaries` cache (`Optional<Summary>`, records need no mixin). |
| `GetActivityQuery` | `activity/application/query/GetActivityQuery.java` | Read path, now `@Cacheable("activities")`. |
| `GetWorkflowEntryQuery` | `workflow/application/query/GetWorkflowEntryQuery.java` | Read path, now `@Cacheable("workflow-entries")`. |
| `UserLookupService` | `user/application/query/UserLookupService.java` | Cross-module lookup, `findById` now `@Cacheable("user-summaries")`. |
| `Create/Update/DeleteActivityUseCase` | `activity/application/usecase/*.java` | Write paths, each now `@CacheEvict("activities")`. |
| `CreateUserUseCase` | `user/application/usecase/CreateUserUseCase.java` | Write path, now `@CacheEvict("user-summaries")`. |
| `WorkflowEntryApplicationService` | `workflow/application/listener/WorkflowEntryApplicationService.java` | Event listener; all 3 handlers now `@CacheEvict("workflow-entries")`. |
| `RedisFixedWindowRateLimiter` | `security/web/RedisFixedWindowRateLimiter.java` | Distributed per-IP fixed-window counter (atomic Lua `INCR`+`EXPIRE`), fail-open on outage. |
| `ThrottleFilter` | `security/web/ThrottleFilter.java` | Burst layer: 20 req/1s per IP → 429 `THROTTLED`. |
| `RateLimitFilter` | `security/web/RateLimitFilter.java` | Quota layer: 100 req/1m per IP → 429 `RATE_LIMITED`. |
| `SecurityRateLimitProperties` | `security/config/SecurityRateLimitProperties.java` | Config surface for CORS, throttle, rate-limit, limiter (`app.security.*`). |
| `CorsConfig` | `security/config/CorsConfig.java` | CORS for browser clients; no credentials (JWT bearer, no cookies). |
| `SecurityConfig` | `security/config/SecurityConfig.java` | Filter chain: CORS → Throttle → RateLimit → JWT auth → authorization; builds limiters as beans, filters inline. |
| Config deltas | `application.yml`, `application-prod.yml`, `application-test.yml` | Redis connection, cache TTL, limiter defaults, prod env-var surface, tests disable limiting. |
| Dependency deltas | `pom.xml`, `docker-compose.yml` | `spring-boot-starter-data-redis`, `-cache`, `-aspectj`, `resilience4j-spring-boot4`; `redis:7-alpine` service + `redis/redisinsight:3.8.0` UI (loopback :5540, preconfigured to the compose redis). |

---

## 2. DECLARATIVE KNOWLEDGE

### The Problem — ASCII

**Problem A: per-IP limiting without an in-memory registry.** The removed `PerIpRateLimiterRegistry` was per-process; two instances meant double budget. The replacement must be atomic, distributed, and self-cleaning.

```
Fixed-window counter for one IP (limit = 100, window = 60s):

count
 101 ┤                                          ✗ 101st request → 429 RATE_LIMITED
 100 ┤          ┌─────────────────────────┐      (Retry-After: 60)
  20 ┤          │  ALLOWED region         │
   1 ┤ ──INCR──►│  (count <= limit)       │
   0 ┤──────────┴─────────────────────────┴──► t
      ▲ t=0     INCR+EXPIRE(60)   t=60    t=120
      │         first request sets TTL; key vanishes with the window → next
      └─ key app:limit:rate-limit:203.0.113.7  window starts fresh (count resets)
```

**Problem B: caching plain domain classes.** `Activity`/`WorkflowEntry` are annotation-free classes with record-style accessors (`name()`) and a private constructor. Jackson 3 does not auto-detect record-style accessors on regular classes — serialization would see no properties and fail.

```
Jackson 3 sees:  Activity { name(), status(), ... }  private ctor, no getters
                      │
                      ▼
              no properties found ✗
                      │
 mixin declares the shape (ActivityCacheMixin):
   @JsonProperty abstract String name(); ... @JsonCreator static restore(...)
                      │
                      ▼
        JSON: {"id":"...","name":"Retro","status":"ACTIVE",...}
```

### Core Variables

| Variable | Type | Plain-English meaning |
|---|---|---|
| `layer` | `String` | Limiter identity, `"throttle"` or `"rate-limit"`; part of the Redis key and the fail-open meter tag. |
| `limit` | `int` | Max requests one IP may send per `window`. |
| `window` | `Duration` | Fixed window length; also the Redis key TTL. |
| `refreshPeriod` | `Duration` | Same value as `window` at the filter; its seconds become the `Retry-After` header. |
| `KEY_PREFIX` | `String` = `"app:limit:"` | Redis key namespace for limiter counters. |
| `key` | `String` | Full Redis key: `app:limit:{layer}:{ip}` (limiter) or `{cacheName}:{id}` (cache). |
| `count` | `Long` | Value returned by the Lua `INCR`; the request number inside the current window. |
| `redisFailOpen` | `boolean` | `true` = allow requests when Redis is unavailable; `false` = propagate (fail closed). |
| `remoteAddr` | `String` | `request.getRemoteAddr()` — the client IP that owns a counter. |
| `CODE` / `MESSAGE` | `String` | 429 payload code (`THROTTLED` / `RATE_LIMITED`) and its message. |
| `objectMapper` | `tools.jackson.databind.ObjectMapper` | Jackson 3 mapper that writes the 429 `ApiError` body. |
| `cacheDefaults` | `RedisCacheConfiguration` | Shared defaults bean (TTL 60s, prefix, serializers). |
| `cacheConfigurers` | `List<RedisCacheConfigurer>` | Per-cache configurations contributed by the owning business modules. |
| `cacheName` | `String` | Cache identifier: `activities`, `workflow-entries`, `user-summaries`. |
| `id` / `userId` / `activityId` | `UUID` / `String` | Cache key argument of a `@Cacheable`/`@CacheEvict` method. |
| `result.id` | `UUID` | Key of the just-created aggregate in eviction SpEL. |
| `version` | `Long` | Optimistic-lock token; stale cache reads surface as 409 on update. |
| `timeout` / `connect-timeout` | `Duration` (500ms) | Lettuce command/connect deadline; turns a hung Redis into a fail-open event. |
| `allowedOrigins` | `List<String>` | Browser origins allowed by CORS (default `http://localhost:3000`). |
| `maxAge` | `long` (3600) | Seconds a browser caches a CORS preflight answer. |

### Key Concepts

| Term | Definition |
|---|---|
| Fail-open | On Redis failure: log WARN, count the event, and serve as if Redis were absent (cache miss / request allowed). Availability over strictness. |
| Fail-closed | On Redis failure: propagate the exception (500). Opt-in via `limiter.redis-fail-open=false`. |
| Fixed window | A counter that resets when its key's TTL expires; up to 2× the limit is possible at window edges (classic property, accepted). |
| Burst layer | Short-window limiter (`ThrottleFilter`, 20/1s) that absorbs spikes first — shortest window fails fastest. |
| Quota layer | Long-window limiter (`RateLimitFilter`, 100/1m) capping sustained usage. |
| Evict-after-invoke | Spring default: eviction runs when the method returns, before the transaction commits; a concurrent reader may re-cache the pre-commit value for ≤ TTL. |
| Negative caching | `Optional.empty()` serializes to the bytes `"null"` and is stored; missing users are cached as misses for the TTL. |
| TTL backstop | 60s `time-to-live` bounds every stale-read window even if an eviction is lost. |
| Lua script atomicity | `INCR`+`EXPIRE` run as one Redis script — no race between two requests. |
| Mixin | Jackson annotations applied to another class; the annotated domain class stays annotation-free. |
| Preflight | Browser OPTIONS request answered by the CORS filter before any limiter — no permit consumed. |
| Stampede | Concurrent misses on a cold key all hit the DB; accepted at template scale. |

---

## 3. DATA STRUCTURES

```java
// security/config/SecurityRateLimitProperties.java:21-46
@ConfigurationProperties(prefix = "app.security")
public record SecurityRateLimitProperties(
        Cors cors,          // CORS origins for browser clients
        Throttle throttle,  // burst layer settings
        RateLimit rateLimit,// quota layer settings
        Limiter limiter) {  // Redis-outage behavior

    public record Cors(List<String> allowedOrigins) {}                    // e.g. ["http://localhost:3000"]
    public record Throttle(boolean enabled, int limitForPeriod,          // true, 20, 1s
                           Duration limitRefreshPeriod) {}
    public record RateLimit(boolean enabled, int limitForPeriod,         // true, 100, 1m
                            Duration limitRefreshPeriod) {}
    public record Limiter(boolean redisFailOpen) {}                      // true
}
```

```java
// shared/RedisCacheConfigurer.java:15-21 — contribution contract
public interface RedisCacheConfigurer {
    String cacheName();                                  // e.g. "activities"
    RedisCacheConfiguration configuration();            // serializer + TTL for that cache
}
```

```java
// shared/ApiError.java:16-29 — the 429 payload (same contract as every other error)
public record ApiError(String code, String message, Instant timestamp, String path,
                       List<FieldError> fieldErrors) {
    public static ApiError of(String code, String message, String path) { ... }   // fieldErrors = null
}
```

| Key pattern | Example | Owner |
|---|---|---|
| `app:limit:{layer}:{ip}` | `app:limit:rate-limit:203.0.113.7` | limiter counter, TTL = window |
| `activities:{id}` | `activities:9f8e7d6c-...` | `Activity` JSON |
| `workflow-entries:{activityId}` | `workflow-entries:9f8e7d6c-...` | `WorkflowEntry` JSON |
| `user-summaries:{userId}` | `user-summaries:11111111-...` | `Optional<Summary>` JSON (`"null"` when empty) |

```java
// user/UserLookup.java:15 — cached value type (record, native Jackson 3 support)
record Summary(String id, String name) {}
```

---

## 4. ALGORITHM DIAGRAMS

### 4.1 Limiter decision (RedisFixedWindowRateLimiter.allow, RedisFixedWindowRateLimiter.java:68-86)

```
input: ip = "203.0.113.7", layer = "rate-limit", limit = 100, window = 60s
  │
  ▼
key = "app:limit:rate-limit:203.0.113.7"
  │
  ▼  Lua (lines 40-44), executed via StringRedisTemplate (line 73):
     local current = redis.call('INCR', KEYS[1])
     if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
     return current
  │
  ▼
count = 101                       count = 7
  │                                  │
  ▼                                  ▼
101 <= 100 ? false                7 <= 100 ? true
  │                                  │
  ▼                                  ▼
allow() = false → 429             allow() = true → filterChain.doFilter(...)
```

Worked example (limits 100 req/1m): requests 1–100 pass (`count 1..100 ≤ 100`); request 101 → `count=101`, `101 <= 100` is false → `false` → 429 `RATE_LIMITED`, `Retry-After: 60` (RateLimitFilter.java:73-77). The key expires at t+60s and the budget resets.

Guard logic:
- Actuator bypass: `shouldNotFilter` returns true for `/actuator/*` (RateLimitFilter.java:57-60, ThrottleFilter.java:57-60).
- Null script result → `count == null || count <= limit` → fails **closed** (RedisFixedWindowRateLimiter.java:85).

### 4.2 Fail-open branch (RedisFixedWindowRateLimiter.java:74-82)

```
input: RedisConnectionFailureException | RedisSystemException (500ms timeout, spring.data.redis.timeout)
  │
  ▼
redisFailOpen == true? ──no──► throw e (fail closed → 500)
  │
  ▼ yes
log.warn("Redis unavailable for limiter layer {layer} - failing open ...")
meterRegistry.counter("app.security.limiter.failopen", "layer", layer).increment()   // line 78
return true                                                                           // allow
```

The `RedisSystemException` branch matters: a slow/hung Redis raises it via the configured 500ms command timeout, so the thread is never blocked — it becomes a counted fail-open event instead.

### 4.3 Cache read (GetActivityQuery.java:29)

```
input: id = 9f8e7d6c-..., key = "activities:9f8e7d6c-..."
  │
  ▼  @Cacheable advisor
Redis GET key
  ├─ hit ──► JacksonJsonRedisSerializer<Activity> deserialize (mixin shape)
  │          → return Activity (no DB)                     [CacheIntegrationTest: 2nd GET]
  ├─ miss ──► method body: activityRepository.findById(id) → DB
  │          → PUT key (TTL 60s) → return Activity
  └─ error ─► FailOpenCacheErrorHandler.handleCacheGetError → WARN + treat as miss
              (FailOpenCacheErrorHandler.java:19-22)
```

### 4.4 Cache write / evict (CreateActivityUseCase.java:43-46)

```
input: execute("Retro", "5k run", actor) → Activity(id = 9f8e7d6c-...)
  │
  ▼ method returns (afterInvocation default)
@CacheEvict(key = "#result.id") → DEL "activities:9f8e7d6c-..."
  │  ← runs BEFORE the @Transactional commit (pinned by code comment, lines 43-45)
  ▼
commit; AfterCommitMetrics; publish ActivityCreated
  │
  ▼ workflow listener (REQUIRES_NEW)
WorkflowEntryApplicationService.onActivityCreated → @CacheEvict "workflow-entries:9f8e7d6c-..."
```

Why not `beforeInvocation=true`: on rollback the cache would be cold for a value that still exists (comment, CreateActivityUseCase.java:44-45).

### 4.5 Filter chain (SecurityConfig.java:93-142)

```
request ──► CorsFilter ──► ThrottleFilter ──► RateLimitFilter ──► SecurityContextHolderFilter
           (preflight      20 req/1s          100 req/1m          + RequestLoggingFilter
            OPTIONS        code=THROTTLED     code=RATE_LIMITED    (addFilterAfter, line 104)
            short-circuits)      │                  │
                                 ▼                  ▼
                          429 + Retry-After: 1   429 + Retry-After: 60
                                 │                  │
                                 ▼                  ▼
                        JWT auth + authorization matrix (lines 105-121)
                        e.g. GET /api/v1/activities/** → authenticated()
                             PUT  /api/v1/activities/** → hasAnyRole("USER","ADMIN")
                        │
                        ▼
                  ActivityController → query/use-case → DB
```

Both limiters are constructed inline when enabled (lines 133-140) and registered after `CorsFilter.class` in order: throttle first, then rate-limit — the shortest window fails fastest. Disabled layers are absent entirely (test profile).

### 4.6 Mode comparison

| | Throttle (burst) | Rate limit (quota) |
|---|---|---|
| Bean | `throttleLimiter` (SecurityConfig.java:73-78) | `rateLimitLimiter` (SecurityConfig.java:82-87) |
| `layer` | `"throttle"` | `"rate-limit"` |
| Defaults | 20 req / 1s | 100 req / 1m |
| 429 `code` | `THROTTLED` | `RATE_LIMITED` |
| `Retry-After` | `1` | `60` |
| Purpose | smooth spikes | cap sustained usage |

---

## 5. EVENT LIFECYCLE

One complete operation with concrete values: `PUT /api/v1/activities/9f8e7d6c-5b4a-4c3d-9e2f-1a2b3c4d5e6f` from IP `203.0.113.7`, JWT `sub=11111111-2222-3333-4444-555555555555`, role `USER`. Body `{"name":"Retro Q2","version":0}`. Cache was warm with `name="Retro"`, `version=0`.

```
1. User/SPA        PUT /api/v1/activities/9f8e7d6c-...  Origin: http://localhost:3000
   (browser)       Authorization: Bearer <JWT>          (preflight OPTIONS already passed, cached 3600s)

2. CorsFilter      Origin in allowed-origins → pass. Preflight never reaches limiters.

3. ThrottleFilter  allow("203.0.113.7") → INCR "app:limit:throttle:203.0.113.7" = 7
                   7 <= 20 → pass

4. RateLimitFilter allow("203.0.113.7") → INCR "app:limit:rate-limit:203.0.113.7" = 12
                   12 <= 100 → pass

5. JWT auth        sub/role validated (HMAC local); ROLE_USER granted
                   matcher PUT /api/v1/activities/** → hasAnyRole("USER","ADMIN") → pass

6. Controller      ActivityController.update (ActivityController.java:75-79) →
                   UpdateActivityUseCase.execute(ActivityId, "Retro Q2", version=0)

7. Use case        optimistic lock: stored version 0 == expected 0 → mutate
                   name "Retro Q2", status DRAFT→ACTIVE, version→1

8. Cache evict     @CacheEvict("activities", key="#id") → DEL "activities:9f8e7d6c-..."
                   (runs before commit; a concurrent reader could re-cache
                   {"name":"Retro","version":0} for ≤ 60s — safe: stale update → 409)

9. Commit          tx commits → AfterCommitMetrics counts → publishes ActivityUpdated

10. Listener       WorkflowEntryApplicationService.onActivityUpdated (REQUIRES_NEW,
                   WorkflowEntryApplicationService.java:49-53) →
                   syncFromActivity("Retro Q2") → status UPDATED
                   @CacheEvict("workflow-entries", key="#activityId") → DEL "workflow-entries:9f8e7d6c-..."

11. Response       200 {"id":"9f8e7d6c-...","name":"Retro Q2","status":"ACTIVE","version":1,...}
```

**Contrast — the 429 path (same IP, 101st request within the minute):**

```
3. ThrottleFilter  INCR = 3 (1s window reset) → 3 <= 20 → pass
4. RateLimitFilter INCR = 101 → 101 <= 100 false → 429
   body: {"code":"RATE_LIMITED","message":"Rate limit exceeded, please retry later",
          "timestamp":"...","path":"/api/v1/activities/9f8e7d6c-...","fieldErrors":null}
   headers: Retry-After: 60, Content-Type: application/json
   (RateLimitFilter.java:71-77)
```

---

## 6. FULL-STACK FLOW

```
User/SPA      Redis                        Security chain          Cache advisor          Application               JPA / PostgreSQL
────────────  ───────────────────────────  ──────────────────────  ─────────────────────  ────────────────────────  ───────────────────
             ┌─────────────────────────┐
             │ app:limit:throttle:ip   │◄──INCR/EXPIRE── ThrottleFilter ─ 429 THROTTLED ─┐
 GET/PUT ──► │ app:limit:rate-limit:ip │◄──INCR/EXPIRE── RateLimitFilter ─ 429 RATE_LIMITED┘
 (Bearer JWT)│ activities:<id>         │
             │ workflow-entries:<id>   │◄──GET/PUT── @Cacheable advisor
             │ user-summaries:<userId> │              (FailOpenCacheErrorHandler on error)
             └─────────────────────────┘
                  ▲ hit                     │ miss / fail-open
                  │                         ▼
                  └── JSON deserialize ◄── Query (GetActivityQuery / GetWorkflowEntryQuery /
                                          UserLookupService) ──► Repository ──► SELECT (read)
                  ▲ put (60s TTL)
                  │
             evict on write ◄── @CacheEvict on UseCases / WorkflowEntryApplicationService
                                  │
                                  ▼
                            @Transactional commit ──► INSERT/UPDATE (write)
                                  │
                                  ▼
                            ApplicationEventPublisher ──► workflow listener (REQUIRES_NEW)
                                                          evicts workflow-entries:...
                                  │
                                  ▼
                            200 / 201 response ◄── Controller assembles response
```

---

## 7. DESIGN DECISIONS

| Decision | Why X instead of Y | What breaks without it |
|---|---|---|
| Fail-open limiter + cache (CacheConfig.java:51-54; RedisFixedWindowRateLimiter.java:74-82) | Availability > strict limiting; outage = slow DB reads, not 500s. `redis-fail-open=false` opts into fail-closed. | A Redis blip 500s every request; the rate limit would be a second SPOF. Trade-off: limit silently off — alert on `app.security.limiter.failopen`. |
| Evict-after-invoke default (comment at every eviction site) | `beforeInvocation=true` cold-caches on rollback. | Rollback leaves cache empty for a value that still exists → one extra DB read per miss; worse: inconsistent cold cache. |
| Fixed window, Lua `INCR`+`EXPIRE` | Atomic across instances, no idle-eviction sweep (key TTL = window). Sliding window/token bucket would need extra bookkeeping or a second key. | In-memory registry returned (removed `PerIpRateLimiterRegistry`): per-process budgets, double limit at 2 instances. |
| 500ms `spring.data.redis.timeout` | Hung Redis raises `RedisSystemException` → fail-open event. | Request threads blocked on a dead Redis; "fail-open" never triggers. |
| Module-local mixins (`ActivityCacheMixin`, `WorkflowEntryCacheMixin`) | Jackson 3 doesn't auto-detect record-style accessors on plain classes; domain stays annotation-free. | Cache serialization fails (no properties) or domain gains Jackson annotations. |
| `RedisCacheConfigurer` contributor pattern | Per-cache serializers reference business types; `shared` never imports them (boundary enforced). | `shared` → business-type dependency breaks Modulith verification. |
| `Optional.empty()` → `"null"` bytes + `cache-null-values: false` | Missing users are negative-cached for TTL; `CreateUserUseCase` evicts to heal. | Every creator lookup for a missing user hits the DB. |
| Filters constructed in `SecurityConfig` (lines 133-140), not beans | Added to chain exactly once, only when enabled. | Duplicate registration or unconditional limiting in tests. |
| CORS `allowCredentials(false)` (CorsConfig.java:30) | JWT bearer header, no cookies → wildcard headers stay legal. | Credentials mode forbids `*` headers and requires exact origin matching. |
| Actuator excluded (`shouldNotFilter`, lines 57-60) | Health probes + Prometheus scraper must never be limited. | Self-inflicted outage: scraper trips its own limit. |
| Resilience4j on classpath, unused (pom.xml comment) | Ready for the first outbound call; nothing speculative (zero outbound HTTP today). | (No breakage — purely additive.) |
| 60s TTL backstop (`spring.cache.redis.time-to-live`) | Bounds every stale-read window even when an eviction is lost (outage). | Stale reads persist indefinitely. Optimistic locking (409) makes even the 60s window safe. |

---

## 8. EDGE CASES TABLE

| Scenario | How handled | Source |
|---|---|---|
| Redis down during cache GET | WARN + swallow → miss → DB read | FailOpenCacheErrorHandler.java:19-22 |
| Redis down during PUT/evict | WARN + swallow → stale entry expires via TTL | FailOpenCacheErrorHandler.java:25-28 |
| Redis down during limiter check | WARN + meter + allow (or propagate if `redisFailOpen=false`) | RedisFixedWindowRateLimiter.java:74-82 |
| Hung/slow Redis | `RedisSystemException` from 500ms timeout → same fail-open path | RedisFixedWindowRateLimiter.java:74; application.yml `spring.data.redis.timeout: 500ms` |
| Lua returns null | `count == null || count <= limit` → fail **closed** | RedisFixedWindowRateLimiter.java:85 |
| 2× burst at window edge | Accepted fixed-window property (documented) | docs/security.md §8 |
| Concurrent reader during write tx | Re-caches pre-commit value ≤ 60s; stale update → 409 via `@Version` | evict comments, ActivityCacheConfiguration/use cases |
| Eviction lost on outage | TTL backstop expires entry | application.yml `time-to-live: 60s` |
| Duplicate event delivery | Listener guard `findByActivityId(...).isPresent() → return` | WorkflowEntryApplicationService.java:42 |
| Rollback after evict | Evict-after-invoke: value still in DB, cache cold — safe (not beforeInvocation) | CreateActivityUseCase.java:43-45 |
| Missing user lookup | `Optional.empty()` → `"null"` bytes stored → negative cache; healed by create evict | UserSummaryCacheConfiguration.java:30-33; UserLookupService.java:31-35 |
| Actuator probes / Prometheus scrape | `/actuator/*` bypass both limiters | RateLimitFilter.java:57-60, ThrottleFilter.java:57-60 |
| CORS preflight OPTIONS | Short-circuited by CorsFilter before limiters — no permit consumed | SecurityConfig.java:96, 129-140 |
| Layer disabled (test profile) | Constructed only when `enabled` → absent from chain | SecurityConfig.java:133-140; application-test.yml |
| Anonymous endpoints (login/refresh) | Limiters sit before authentication → covered | SecurityConfig.java:129-140 (before auth) |
| Reverse proxy in front | All clients collapse to proxy IP — one shared budget; documented, move limiting to gateway or terminate proxy | docs/security.md §8 |
| Stampede on cold key | Multiple DB reads; accepted at template scale | docs/architecture.md §8a |
| Unknown `activityId` | 404 from repository; exceptions are not cached (only returned values are) | GetActivityQuery.java:30-32 |
| New user created | `@CacheEvict` on a never-existing key — harmless no-op keeps the "all writes evict" invariant | CreateUserUseCase.java:35 |
| 429 body/headers | `ApiError` + RFC 6585 `Retry-After` | RateLimitFilter.java:73-77, ThrottleFilter.java:73-77 |

---

## 9. INTEGRATION POINT

Real call sites, each argument annotated:

```java
// RateLimitFilter.java:66 — quota layer, one atomic counter check per request
if (limiter.allow(request.getRemoteAddr())) {   // getRemoteAddr() = the per-IP budget owner
    filterChain.doFilter(request, response);    // under budget → continue down the chain
    return;
}
// over budget → 429 (lines 73-77)
```

```java
// RedisFixedWindowRateLimiter.java:73 — the atomic counter primitive
count = redisTemplate.execute(
        INCR_AND_EXPIRE,                                    // Lua: INCR; EXPIRE only when count==1
        List.of(key),                                       // KEYS[1] = "app:limit:rate-limit:203.0.113.7"
        String.valueOf(window.toSeconds()));                // ARGV[1] = "60" (StringRedisSerializer)
```

```java
// GetActivityQuery.java:29 — read path cache
@Transactional(readOnly = true)
@Cacheable(cacheNames = "activities", key = "#id")          // Redis key "activities:9f8e7d6c-..."
public Activity findById(ActivityId id) { ... }
```

```java
// CreateActivityUseCase.java:46 — write path eviction (result.id = new aggregate id)
@Transactional
@CacheEvict(cacheNames = "activities", key = "#result.id")
public Activity execute(String name, String description, CurrentUser actor) { ... }
```

```java
// SecurityConfig.java:133-140 — layer registration, order preserved for same-anchor filters
if (properties.throttle().enabled()) {                      // burst first: shortest window fails fastest
    http.addFilterAfter(new ThrottleFilter(throttleLimiter, // bean from line 73-78
            properties.throttle().limitRefreshPeriod(),     // 1s → Retry-After: 1
            objectMapper),                                   // Jackson 3, writes ApiError
            CorsFilter.class);                               // anchor: after CORS, preflights already handled
}
if (properties.rateLimit().enabled()) {
    http.addFilterAfter(new RateLimitFilter(rateLimitLimiter, properties.rateLimit().limitRefreshPeriod(),
            objectMapper), CorsFilter.class);
}
```

```java
// ActivityCacheConfiguration.java:24-42 — per-cache config contribution
@Bean
RedisCacheConfigurer activityCacheConfigurer(RedisCacheConfiguration cacheDefaults) {
    ObjectMapper mapper = JsonMapper.builder()
            .addMixIn(Activity.class, ActivityCacheMixin.class)   // property shape for the plain domain class
            .build();
    RedisCacheConfiguration configuration = cacheDefaults.serializeValuesWith(   // inherits TTL/prefix from shared
            RedisSerializationContext.SerializationPair.fromSerializer(
                    new JacksonJsonRedisSerializer<>(mapper, Activity.class)));  // typed: deserializes to Activity
    return new RedisCacheConfigurer() {
        public String cacheName() { return "activities"; }                        // CacheConfig.java:46 wiring key
        public RedisCacheConfiguration configuration() { return configuration; }
    };
}
```

---

## 10. FILE MAP

```
modular-monolith/
├── docker-compose.yml                     # + redis:7-alpine service (healthcheck, app depends_on) + redis-insight UI (:5540, loopback)
├── pom.xml                                # + data-redis, cache, aspectj starters; resilience4j-spring-boot4 2.4.0
├── src/main/resources/
│   ├── application.yml                    # redis conn/timeouts, cache TTL, app.security.* defaults
│   ├── application-prod.yml               # REDIS_* env surface, LIMITER_REDIS_FAIL_OPEN
│   └── application-test.yml               # throttle/rate-limit disabled (layers absent from chain)
├── src/main/java/com/example/app/
│   ├── shared/                            # root = public contract; internals grouped by responsibility
│   │   ├── RedisCacheConfigurer.java      #    per-cache contribution contract (root, cross-module)
│   │   ├── cache/
│   │   │   ├── CacheConfig.java           #    RedisCacheManager assembly + fail-open error handler
│   │   │   ├── CacheDefaultsConfig.java   #    TTL 60s, prefix, string keys, JSON values
│   │   │   └── FailOpenCacheErrorHandler.java # swallows GET/PUT/EVICT/CLEAR errors
│   ├── security/
│   │   ├── config/
│   │   │   ├── CorsConfig.java            #    CORS source, no credentials, maxAge 3600
│   │   │   ├── SecurityRateLimitProperties.java  #    app.security.* config record
│   │   │   └── SecurityConfig.java        #    chain wiring + limiter beans + registration
│   │   └── web/
│   │       ├── RedisFixedWindowRateLimiter.java  #    Lua INCR+EXPIRE counter, fail-open
│   │       ├── ThrottleFilter.java        #    burst layer (THROTTLED, 20/1s)
│   │       └── RateLimitFilter.java       #    quota layer (RATE_LIMITED, 100/1m)
│   ├── activity/
│   │   ├── infrastructure/cache/          #    ActivityCacheConfiguration + ActivityCacheMixin
│   │   ├── application/query/GetActivityQuery.java        # @Cacheable("activities")
│   │   └── application/usecase/{Create,Update,Delete}ActivityUseCase.java  # @CacheEvict("activities")
│   ├── user/
│   │   ├── infrastructure/cache/UserSummaryCacheConfiguration.java  # Optional<Summary>, no mixin
│   │   ├── application/query/UserLookupService.java                # @Cacheable("user-summaries")
│   │   └── application/usecase/CreateUserUseCase.java              # @CacheEvict("user-summaries")
│   └── workflow/
│       ├── infrastructure/cache/          #    WorkflowEntryCacheConfiguration + WorkflowEntryCacheMixin
│       ├── application/listener/WorkflowEntryApplicationService.java  # 3× @CacheEvict("workflow-entries")
│       └── application/query/GetWorkflowEntryQuery.java              # @Cacheable("workflow-entries")
└── src/test/java/com/example/app/
    ├── unit/                              # no Spring, no Docker
    │   ├── RedisFixedWindowRateLimiterTest.java     # window counting, fail-open conn+timeout, fail-closed
    │   ├── RateLimitFilterTest.java / ThrottleFilterTest.java  # per-IP 429 contract, Retry-After, actuator
    │   ├── FailOpenCacheErrorHandlerTest.java        # 4 handlers swallow errors
    │   ├── CorsConfigTest.java                       # origins/methods/headers/no-credentials
    │   └── {Activity,WorkflowEntry,UserSummary}CacheSerializationTest.java  # round-trips + "null" negative cache
    └── integration/                       # Testcontainers (PostgreSQL + Redis)
        ├── AbstractIntegrationTest.java   # + redis:7-alpine GenericContainer, dynamic properties
        ├── CacheIntegrationTest.java      # populate/evict e2e for all 3 caches
        ├── RateLimitIntegrationTest.java  # 3/1m → 429 RATE_LIMITED, Retry-After 60
        └── ThrottleIntegrationTest.java   # 2/1s → 429 THROTTLED, Retry-After 1
```