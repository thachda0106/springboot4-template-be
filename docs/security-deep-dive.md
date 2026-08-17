# Deep Dive — Spring Security OAuth2 Resource Server Module

A line-level walkthrough of `src/main/java/com/example/app/security/`, written for
someone who has never read the source. It explains what each file does, why the
architecture is shaped this way, and how one HTTP request flows through the whole
chain — from raw bearer token to domain logic and back.

---

## 1. Overview Table

| Name | Path | Role (one sentence) |
|---|---|---|
| `SecurityConfig` | `security/SecurityConfig.java` | Builds the HTTP filter chain that authenticates every request via JWT and enforces scope-based URL rules |
| `CurrentUserProvider` | `security/CurrentUserProvider.java` | Interface that hands the application the authenticated user without exposing Spring Security types |
| `SecurityContextCurrentUserProvider` | `security/SecurityContextCurrentUserProvider.java` | The single component that reads the `SecurityContext` and turns a JWT's `sub` claim into a `CurrentUser` |
| `CurrentUser` | `security/CurrentUser.java` | Plain record carrying the authenticated user's id — the only security type the domain ever sees |
| `RestAuthenticationEntryPoint` | `security/RestAuthenticationEntryPoint.java` | Writes a consistent JSON 401 when a request arrives without a valid token |
| `RestAccessDeniedHandler` | `security/RestAccessDeniedHandler.java` | Writes a consistent JSON 403 when an authenticated request lacks the required scope |
| `LocalJwtDecoderConfig` | `security/LocalJwtDecoderConfig.java` | Creates the HMAC-HS256 JWT decoder used in local/test profiles only |

---

## 2. Declarative Knowledge

Concepts first; algorithms after. Nothing here assumes you have read the source.

### 2.1 The Problem

This application issues no tokens and stores no passwords. It must still (a) prove
that every incoming request carries a token signed by a trusted Identity Provider
(**authentication**), and (b) decide which endpoints that caller may hit based on
the token's scopes (**authorization**). At the same time the business logic must
stay blind to how the caller was authenticated, so it stays testable and reusable
by batch jobs or message consumers.

```text
                          ┌─────────────────────────────────────────────┐
                          │          External Identity Provider        │
                          │   (Keycloak / Auth0 / Cognito / Okta)      │
                          └───────────────────┬─────────────────────────┘
                                              │ issues a signed JWT
                                              ▼
   HTTP request                        ┌──────────────┐
   Authorization: Bearer <JWT>  ─────► │ Filter chain │ 1. validate signature + expiry
                                      └──────┬───────┘ 2. map scope claims → authorities
                                             │
                                             ▼
                                     ┌──────────────┐
                                     │ Controller   │ ← gets CurrentUser (id only)
                                     └──────┬───────┘
                                            ▼
                                     ┌──────────────┐
                                     │ Use case     │ ← sees a plain record, never a Jwt
                                     └──────┬───────┘
                                            ▼
                                     ┌──────────────┐
                                     │ Domain       │ ← zero security types
                                     └──────────────┘
```

Without this split, domain code would read `SecurityContext` directly and become
untestable and framework-coupled (see Design Decisions §7).

### 2.2 Core Variables table

| Variable | Type | Plain-English meaning |
|---|---|---|
| `http` | `HttpSecurity` | Builder that assembles Spring Security's filter chain for all HTTP requests |
| `authenticationEntryPoint` | `RestAuthenticationEntryPoint` | Object that writes the 401 JSON response when no valid token is present |
| `accessDeniedHandler` | `RestAccessDeniedHandler` | Object that writes the 403 JSON response when a token is valid but lacks the needed scope |
| `authentication` | `Authentication` | The caller's identity object stored in the per-request `SecurityContext` |
| `jwtAuthenticationToken` | `JwtAuthenticationToken` | An `Authentication` whose identity is the parsed JWT; source of the `sub` claim |
| `secretKey` | `String` | Shared HMAC secret from config; only used in local/test to verify tokens |
| `key` | `SecretKey` | The `secretKey` bytes wrapped for use by the HS256 decoder |
| `objectMapper` | `ObjectMapper` | Jackson (3 / `tools.jackson`) tool that turns `ApiError` objects into JSON on the response |
| `actor` | `CurrentUser` | The authenticated user passed from a controller into a use case |
| `request` | `HttpServletRequest` | The raw HTTP request; its URI is echoed into error payloads |
| `currentUserId` | `String` | The `sub` claim extracted from the token; used to look up the user's own profile |

### 2.3 Key Concepts table

| Term | Definition |
|---|---|
| JWT | JSON Web Token: a signed token with a header, claims and a signature; the identity proof presented in `Authorization: Bearer ...` |
| `sub` claim | The subject claim — the identifier of the authenticated user, treated as the user id everywhere in this app |
| scope | A space-separated string claim (`scope` or `scp`) listing what the token may do, e.g. `activity:write` |
| authority | A granted permission Spring Security matches against URL rules; here always `SCOPE_<scope>` |
| `SecurityContext` | Spring Security's per-request box holding the current `Authentication` |
| Bearer token | An access token sent in the `Authorization` header, with no session or cookie |
| 401 vs 403 | 401 = unauthenticated (no/invalid token); 403 = authenticated but not permitted |
| OIDC discovery | The IdP's well-known endpoint that exposes its signing keys so signatures can be verified |
| HMAC | Shared-secret symmetric signing (HS256 here); signer and verifier use the same secret |
| Stateless | No server-side session; every request is authenticated independently from the token |

---

## 3. Data Structures

The types the algorithms below rely on.

```text
// security/CurrentUser.java — app-level identity, framework-free
record CurrentUser(String id)          // id = the JWT "sub" claim
  static CurrentUser of(String id)     // named constructor, e.g. CurrentUser.of("user-1")

// security/CurrentUserProvider.java — boundary abstraction
interface CurrentUserProvider
  CurrentUser currentUser()            // the authenticated user; throws if none

// shared/ApiError.java — one error shape for every failure path
record ApiError(
    String code,                       // machine code: "UNAUTHORIZED", "FORBIDDEN", ...
    String message,                    // human message
    Instant timestamp,                 // when the error occurred (Instant.now())
    String path,                       // request URI that failed
    List<FieldError> fieldErrors)      // per-field validation errors, or null
  record FieldError(String field, String message)

// (external, Spring Security) JwtAuthenticationToken — what the filter stores
class JwtAuthenticationToken implements Authentication
  Jwt getToken()                       // parsed JWT; getSubject() → sub claim
```

---

## 4. Algorithm Diagrams

### 4.1 Filter-chain architecture

```text
HTTP request (Authorization: Bearer <JWT>)
   │
   ▼
SecurityFilterChain  (SecurityConfig.securityFilterChain, L42)
   │  csrf disabled (L44)
   │  sessions STATELESS (L45)
   ▼
OAuth2 Resource Server  (L56-58)
   │  BearerTokenAuthenticationFilter:
   │    JwtDecoder validates signature + expiry   (LocalJwtDecoderConfig L36-38)
   │    → JwtAuthenticationToken (principal = Jwt)
   │    → scope/scp claims → authorities "SCOPE_..."  (default converter)
   ▼
authorizeHttpRequests  (L46-55)
   │  URL + method matched against rules in §4.4
   ├── no/invalid token ───────────► RestAuthenticationEntryPoint.commence (L28-34) → 401
   ├── missing authority ──────────► RestAccessDeniedHandler.handle (L28-35)  → 403
   ▼
Controller (calls CurrentUserProvider.currentUser() → CurrentUser)
```

### 4.2 JWT validation formula

```text
input: raw token "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEiLCJzY29wZSI6InNjb3BlIDEifQ.sig"
   → base64url-decode header + claims
   → verify HMAC-SHA256 signature against secretKey bytes      (LocalJwtDecoderConfig L35)
   → verify exp claim is still in the future (60s clock skew allowed)  (Nimbus default)
   → prod only: verify iss matches issuer-uri via OIDC discovery
output: valid Jwt → JwtAuthenticationToken   (or AuthenticationException → 401)
```

### 4.3 Scope → authority mapping (concrete)

```text
input:  scope claim = "activity:read activity:write"
   → split on single spaces
   → prefix each token with "SCOPE_"
output: authorities = {SCOPE_activity:read, SCOPE_activity:write}
```

Check: `POST /api/activities` requires `SCOPE_activity:write` (SecurityConfig L49),
so a token scoped only `activity:read` passes authentication but is denied → 403.

### 4.4 URL matching decision flow (SecurityConfig L46-55)

```text
request arrives
  ├─ GET /actuator/health | /actuator/info → permitAll                       (L47)
  ├─ GET    /api/activities/**             → authenticated                   (L48)
  ├─ POST   /api/activities/**             → hasAuthority SCOPE_activity:write (L49)
  ├─ PUT    /api/activities/**             → hasAuthority SCOPE_activity:write (L50)
  ├─ DELETE /api/activities/**             → hasAuthority SCOPE_activity:admin (L51)
  ├─ GET    /api/workflow-entries/**       → authenticated                   (L52)
  ├─ POST   /api/users                     → hasAuthority SCOPE_user:write   (L53)
  ├─ GET    /api/users/**                  → authenticated                   (L54)
  └─ anything else                         → authenticated                   (L55)
```

### 4.5 Decoder mode comparison

```text
LOCAL / TEST (development)                    PRODUCTION
────────────────────────────                  ─────────────────────────────
app.security.jwt.secret-key set                spring.security.oauth2.resourceserver.jwt.
  (application-local.yml L14,                    issuer-uri = ${JWT_ISSUER_URI}
   application-test.yml L6)                     (application-prod.yml L14)
LocalJwtDecoderConfig bean ACTIVE              LocalJwtDecoderConfig bean ABSENT
  (@ConditionalOnProperty L33)                  (property not set → bean not created)
NimbusJwtDecoder HS256 with shared secret      Boot auto-config: OIDC discovery,
  (L36-38)                                       downloads the IdP's JWK set
validates: signature, expiry                   validates: signature, expiry, issuer
  (no issuer check — documented dev trade-off)   (audience if configured)
```

---

## 5. Event Lifecycle — one full operation

Concrete values: `POST /api/activities`, token `sub=user-1`,
`scope="activity:read activity:write"`, local mode,
secret `local-dev-secret-change-me-0123456789abcdef`.

```text
Step  Client sends POST /api/activities
      Authorization: Bearer eyJ...  (minted via scripts/mint-local-jwt.py --sub user-1 ...)
  1.  Filter chain: CSRF off, STATELESS                        SecurityConfig L44-45
  2.  BearerTokenAuthenticationFilter decodes and verifies the HS256 signature
      with secret "local-dev-secret-change-me-..."             LocalJwtDecoderConfig L35-38
  3.  → JwtAuthenticationToken(subject = "user-1")
      → authorities SCOPE_activity:read, SCOPE_activity:write   (default converter)
  4.  authorizeHttpRequests: POST /api/activities/** needs
      SCOPE_activity:write → PASS                               SecurityConfig L49
  5.  ActivityController.create called                          ActivityController L59
      createActivityUseCase.execute(name, description,
          currentUserProvider.currentUser())                    ActivityController L61-62
  6.  SecurityContextCurrentUserProvider.currentUser():
      SecurityContextHolder → JwtAuthenticationToken
      → CurrentUser.of(sub) = CurrentUser(id = "user-1")        SecurityContextCurrentUserProvider L18-20
  7.  CreateActivityUseCase.execute(name, description, actor):
      @Transactional opens a DB transaction                     CreateActivityUseCase L37
      userLookup.findById("user-1") — must exist                L39
      Activity.create(name, description, "user-1") → save       L42-43
      publish ActivityCreated(id, name)                         L45
      transaction commits → WorkflowEventListener creates a workflow entry (out of scope here)
  8.  Response: 201 Created, Location: /api/activities/{id}
      body = ActivityResponse.from(activity)

Failure branches:
  - no token / wrong key / expired / garbage:
      filter throws AuthenticationException →
      RestAuthenticationEntryPoint.commence (L28-34) → 401
      {"code":"UNAUTHORIZED","message":"Authentication is required","path":"/api/activities",...}
  - token valid but only scope "activity:read":
      AuthorizationService denies →
      RestAccessDeniedHandler.handle (L28-35) → 403
      {"code":"FORBIDDEN","message":"You do not have permission to access this resource",...}
```

---

## 6. Full-Stack Flow (horizontal swimlane)

```text
User            Library (Spring Security)    Hooks (CurrentUserProvider)    Feature (activity)          Backend
│  Bearer JWT   │                            │                             │                           │
│──────────────►│ BearerTokenAuthentication  │                             │                           │
│               │ Filter: JwtDecoder →       │                             │                           │
│               │ JwtAuthenticationToken     │                             │                           │
│               │ + SCOPE_ authorities       │                             │                           │
│               │ authorizeHttpRequests rule │                             │                           │
│               │ (SecurityConfig L46-55)    │                             │                           │
│               │────────────►               │                             │                           │
│               │                            │ currentUser() → CurrentUser │                           │
│               │                            │ (SecurityContextHolder L18) │                           │
│               │                            │────────────►                │                           │
│               │                            │                             │ CreateActivityUseCase     │
│               │                            │                             │ userLookup.findById      │
│               │                            │                             │ Activity.create + save   │
│               │                            │                             │ publish ActivityCreated  │
│               │                            │                             │────────────►             │ DB tx + event
│               │                            │                             │◄────────────             │ listener (workflow)
│  ◄────────────│  201 Created + ActivityResponse │                           │                           │
```

---

## 7. Design Decisions

| Decision | Why X instead of Y | What breaks without it |
|---|---|---|
| Externalize authentication (OAuth2 Resource Server) | The app never stores credentials → no hashing, reset flows or breach surface; MFA/SSO/rotation delegated to the IdP | The app would own identity mechanics, contradicting "user module is business data" |
| `CurrentUser` record + `CurrentUserProvider` instead of passing `Jwt`/`Authentication` | Domain stays framework-free and testable (`CreateActivityUseCaseTest` builds `CurrentUser.of("user-1")`) | Domain would couple to Spring Security and become untestable in unit tests |
| Two handlers: 401 vs 403 | Keeps authentication and authorization failures explicit and independently testable | Clients could not distinguish "not authenticated" from "authenticated but not allowed" |
| HMAC local mode via `@ConditionalOnProperty` instead of disabling security | Security is never disabled locally; tokens are still signature-validated | Local dev could run with no auth, silently diverging from production behavior |
| Scope-based authorization on the standard `scope` claim | Provider-agnostic: any OIDC provider works with zero claim mapping | Tying to provider-specific `roles` claims would couple the app to one IdP |
| `STATELESS` sessions + CSRF off | A bearer-token API holds no session state; CSRF only guards cookie-based flows | Sessions would add server-side state, and CSRF would become an unhandled risk |
| `@Configuration(proxyBeanMethods = false)` | No inter-bean method calls → no CGLIB proxy needed | Unnecessary proxying overhead and a stricter bean lifecycle |

---

## 8. Edge Cases Table

| Scenario | How handled | Line refs |
|---|---|---|
| No token on a protected endpoint | 401 `UNAUTHORIZED` JSON | RestAuthenticationEntryPoint L30-34 |
| Wrong HMAC key | signature check fails → 401 | LocalJwtDecoderConfig L35; JwtValidationIntegrationTest L51-61 |
| Expired token | `exp` check fails → 401 | JwtValidationIntegrationTest L64-73 |
| Garbage token (`not.a.jwt`) | decode fails → 401 | JwtValidationIntegrationTest L76-80 |
| Valid token, missing scope (e.g. DELETE without `activity:admin`) | 403 `FORBIDDEN` JSON | RestAccessDeniedHandler L30-34; SecurityIntegrationTest L59-64 |
| Authenticated read of a nonexistent activity | 404 `ACTIVITY_NOT_FOUND` — auth passed, id missing | SecurityIntegrationTest L67-74 |
| Unknown path with a valid token | 404 `NOT_FOUND` | GlobalExceptionHandler L51-55; SecurityIntegrationTest L77-82 |
| `/api/users/me` with a non-UUID `sub` | `IllegalArgumentException` → 400 `MALFORMED_REQUEST` | UserController L63; GlobalExceptionHandler L44-49 |
| SecurityContext holds a non-JWT authentication | `IllegalStateException` (unreachable by construction) → 500 | SecurityContextCurrentUserProvider L22; GlobalExceptionHandler L64-69 |
| Actuator health/info | `permitAll` | SecurityConfig L47 |
| Actuator prometheus | `authenticated` | SecurityConfig L55; SecurityIntegrationTest L28-31 |
| HMAC property accidentally set in prod | impossible: property exists only in local/test yml; bean gated by `@ConditionalOnProperty` | LocalJwtDecoderConfig L33; local L14; test L6; prod L14 |

---

## 9. Integration Point

Actual call site — `ActivityController.java` L58-62:

```java
@PostMapping
public ResponseEntity<ActivityResponse> create(@Valid @RequestBody CreateActivityRequest request,
                                               HttpServletRequest httpRequest) {
    Activity activity = createActivityUseCase.execute(
            request.name(),                       // DTO field: activity display name
            request.description(),                // DTO field: optional description
            currentUserProvider.currentUser());   // ← CurrentUser(id="user-1"), from the JWT sub claim
    ...
```

Second call site — `UserController.java` L60-64 (`GET /api/users/me`):

```java
public UserResponse me() {
    String currentUserId = currentUserProvider.currentUser().id();          // "user-1"
    return UserResponse.from(userLookupService.getById(
            UserId.from(UUID.fromString(currentUserId))));                  // sub must parse as a UUID
}
```

---

## 10. File Map

```text
src/main/java/com/example/app/
├── security/                            ← this deep dive
│   ├── SecurityConfig.java              filter chain; stateless; scope rules; wires handlers
│   ├── CurrentUserProvider.java         boundary abstraction for the authenticated user
│   ├── SecurityContextCurrentUserProvider.java  sole reader of SecurityContext → CurrentUser
│   ├── CurrentUser.java                 framework-free identity record (id = sub claim)
│   ├── RestAuthenticationEntryPoint.java        401 → ApiError JSON
│   ├── RestAccessDeniedHandler.java             403 → ApiError JSON
│   └── LocalJwtDecoderConfig.java               HMAC-HS256 decoder, local/test only
├── activity/
│   └── api/ActivityController.java     consumer: passes currentUser() into the create use case
│   └── application/create/CreateActivityUseCase.java  consumer: takes CurrentUser actor
├── user/
│   └── api/UserController.java         consumer: /me uses currentUser().id()
├── shared/
│   ├── ApiError.java                   error contract used by both handlers
│   └── GlobalExceptionHandler.java     other error paths (400/404/409/500), same ApiError
├── workflow/api/WorkflowController.java  authenticated-only; never uses CurrentUserProvider
├── Application.java                    Spring Boot entry point
src/main/resources/
├── application.yml                     base config (JPA validate, Flyway, actuator)
├── application-local.yml               app.security.jwt.secret-key (local HMAC)
├── application-test.yml                fixed test secret
└── application-prod.yml                spring...issuer-uri (OIDC)
scripts/
└── mint-local-jwt.py                   dev-only HS256 token minter
src/test/java/com/example/app/
├── integration/SecurityIntegrationTest.java        401/403/public-health matrix
├── integration/JwtValidationIntegrationTest.java   real decoder: valid/wrong/expired/garbage
├── integration/AbstractApiIntegrationTest.java    jwt() post-processor helper
├── application/CreateActivityUseCaseTest.java     uses CurrentUser.of("user-1")
└── architecture/ApplicationModularityTests.java   security is a leaf module (depends only on shared)
docs/security.md                        existing high-level security overview
```
