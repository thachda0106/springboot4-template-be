# Redis Cache — Class & Flow Diagrams

Diagrams for the Redis-backed Spring Cache infrastructure. They show how `shared` composes one
app-wide `RedisCacheManager` from per-module contributions and how reads, writes, and failures
flow through it. Line numbers refer to `src/main/java/...` files.

Companion to `docs/redis-cache-and-rate-limiting.md` (the deep dive) and `docs/architecture.md` §8a.

---

## 1. Class diagram

```
                 SHARED module (technical)                          BUSINESS modules

  ┌──────────────────────────────────────────────┐        ┌────────────────────────────────────────┐
  │ RedisCacheConfigurer  (interface)            │        │ ActivityCacheConfiguration           │
  │   module root = public API                  │        │   @Configuration(proxyBeanMethods=false)│
  │   String cacheName()                        │        │   @Bean RedisCacheConfigurer           │
  │   RedisCacheConfiguration configuration()  │        │     activityCacheConfigurer(cacheDefaults)│
  └───────────────▲──────────────────────────────┘        └──────────────┬─────────────────────────┘
                  │ implemented by (activity/workflow/user)               │
                  │                                                      │ uses (derives from) base config
  ┌──────────────────────────────┐                       ┌───────────────▼──────────────────────────┐
  │ CacheConfig                 │                       │ Jackson3 ObjectMapper                  │
  │  @Configuration + @EnableCaching                    │   JsonMapper.builder()                │
  │  implements CachingConfigurer │                     │   .addMixIn(Activity.class,           │
  │  ────────────────────────────│                       │      ActivityCacheMixin.class)        │
  │  fields:                     │                       └──────────────┬─────────────────────────┘
  │   List<RedisCacheConfigurer> │                                  │
  │   RedisConnectionFactory    │                                  ▼
  │  ───────────────────────────│                        ┌────────────────────────────────────────┐
  │  cacheManager(cacheDefaults)│                        │ ActivityCacheMixin (abstract)          │
  │     ▼ builder ─► RedisCacheManager                   │   @JsonProperty abstract id(), name(), │
  │  errorHandler()             │                        │      description(), status(), ...       │
  │     ▼ new FailOpenCacheErrorHandler│                 │   @JsonCreator static restore(...)     │
  └───────────────┬──────────────┘                       └──────────────┬─────────────────────────┘
                  │                                                      │ annotations applied to
                  ▼                                                      ▼
  ┌──────────────────────────────┐                        ┌────────────────────────────────────────┐
  │ FailOpenCacheErrorHandler    │                        │ Activity (domain, annotation-free)     │
  │   implements CacheErrorHandler│                       │   record-style accessors, private ctor  │
  │   handleCacheGet/Put/Evict/ClearError()            │   static restore(...)                     │
  └──────────────────────────────┘                        └────────────────────────────────────────┘

  Callers in activity module:
    GetActivityQuery            → @Cacheable("activities")                       GetActivityQuery.java:29
    Create/Update/DeleteUseCase → @CacheEvict("activities")   CreateActivityUseCase.java:46, etc.
```

Same contributor pattern (typed serializer + mixin) applies in the `workflow` and `user` modules:
`WorkflowEntryCacheConfiguration` / `WorkflowEntryCacheMixin` and `UserSummaryCacheConfiguration`
(records need no mixin — Jackson 3 handles them natively).

## 2. Startup flow — bean wiring

```
CACHE INFRASTRUCTURE STARTUP (once, at app boot)

  CacheDefaultsConfig.cacheDefaults()
    │
    ▼  (bean cacheDefaults)
  base RedisCacheConfiguration
    ├─ TTL     ← spring.cache.redis.time-to-live (60s)         application.yml
    ├─ prefix  ← computePrefixWith(name -> name + ":")          CacheDefaultsConfig.java:31
    ├─ keys    ← RedisSerializer.string()                       CacheDefaultsConfig.java:32
    ├─ values  ← RedisSerializer.json()  ← baseline             CacheDefaultsConfig.java:33
    └─ nulls   ← disableCachingNullValues()                     CacheDefaultsConfig.java:34
    │
    │  injected as parameter into each module config
    ▼
  ActivityCacheConfiguration.activityCacheConfigurer(cacheDefaults)
    ├─ ObjectMapper = JsonMapper.builder().addMixIn(Activity, ActivityCacheMixin)
    └─ configuration = cacheDefaults.serializeValuesWith(
                          JacksonJsonRedisSerializer(mapper, Activity.class))  ← typed override
    │
    ▼  produces RedisCacheConfigurer { cacheName = "activities" }
  (same pattern: workflow → "workflow-entries", user → "user-summaries")
    │
    ▼  collected into List<RedisCacheConfigurer>                   CacheConfig.java:36
  CacheConfig.cacheManager(cacheDefaults)                         CacheConfig.java:43
    │  RedisCacheManager.builder(connectionFactory)
    │     .cacheDefaults(cacheDefaults)            ← base for every cache
    │     .withCacheConfiguration("activities", typedConfig)
    │     .withCacheConfiguration("workflow-entries", ...)
    │     .withCacheConfiguration("user-summaries", ...)
    ▼
  ONE RedisCacheManager → exposed as app-wide CacheManager bean
  CacheConfig.errorHandler() → FailOpenCacheErrorHandler → registered on interceptor
```

Key wiring point: `shared` never imports business types. Each business module contributes its
own typed serializer through the `RedisCacheConfigurer` interface, and `CacheConfig` just
collects the beans.

## 3. Read path — cache hit & miss

```
GET /api/v1/activities/{id}
      │
      ▼
GetActivityQuery.findById(id)
      │   @Cacheable(cacheNames="activities", key="#id")          GetActivityQuery.java:29
      ▼
CacheInterceptor → CacheManager.getCache("activities")
      │
      ▼
RedisCache.get("activities:{id}")
      │
      ├── HIT  ──► return cached Activity (no DB read)
      │
      └── MISS ──► execute real method
                   ActivityRepository.load(id)    (DB read)
                   ├── RedisCache.put("activities:{id}", activity)   (TTL 60s)
                   └── return Activity
```

## 4. Write path — eviction keeps cache consistent

```
CREATE (POST /api/v1/activities)   — Update/Delete analogous
      │
      ▼
CreateActivityUseCase.run()
      │  @Transactional
      ▼
Activity.create(...) ──► repository.save(activity)     (DB insert)
      │
      ▼  same transaction
  publish ActivityCreatedEvent  (ApplicationEventPublisher)
      │
      ▼  after commit
@CacheEvict(cacheNames="activities", key="#result.id")   CacheActivityUseCase.java:46
      │
      └──► Redis DEL "activities:{id}"    (stale entry removed)

          — cross-module (workflow listener) —
ActivityCreatedEvent ──► WorkflowEntryApplicationService
      │  @ApplicationModuleListener (after commit, REQUIRES_NEW tx)
      ▼
create workflow entry ──► @CacheEvict("workflow-entries", key="#activityId")   WorkflowEntryApplicationService.java:40
```

Eviction is *after invoke* (Spring default): if the transaction rolls back, the cached value is
still valid — only the cache is cold, which is safe. A concurrent reader could re-cache the
pre-commit value for at most the TTL; the optimistic-lock `@Version` turns stale updates into 409s.

## 5. Failure path — fail-open

```
                    Redis DOWN / connection error
                              │
                              ▼
RedisCache.get("activities:{id}") throws
                              │
                              ▼
Interceptor calls CacheErrorHandler (not the controller!)
                              │
                              ▼
FailOpenCacheErrorHandler.handleCacheGetError()
      └── log.warn "Cache GET failed ... failing open (DB read)"
                              │
                              ▼
              treated as cache MISS  →  method runs → DB read
                              │
                              ▼
                    200 OK (DB value), value not cached
```

Same handler logs-and-swallows PUT / EVICT / CLEAR errors too. Availability over caching: a Redis
outage degrades to DB reads, never to 500s. The TTL backstop + optimistic locking keep any stale
reads safe.

---

## Key takeaways

- **One `RedisCacheManager` for all modules** — `CacheConfig` composes shared defaults + per-module
  typed serializers contributed via the `RedisCacheConfigurer` interface. `shared` never imports
  business types.
- **Two serialization layers** — generic JSON baseline from `shared`, overridden per-cache with a
  typed `JacksonJsonRedisSerializer` (via mixin) by the owning module.
- **Reads fill the cache, mutations evict** — `@Cacheable` on queries, `@CacheEvict` on write
  use-cases and after activity events.
- **Fail-open is centralized** — any Redis error becomes a logged cache miss, never a 500.