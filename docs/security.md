# Security

The application is its **own token issuer**: `POST /api/v1/auth/login` (email + password)
returns an access token and a refresh token; every request validates the access token.
Authorization is **role-based** (RBAC).

## 1. The full flow

```text
Client
   │  POST /api/v1/auth/login {email, password}
   ▼
LoginUseCase (user module)
   │  • normalize email (trim + lowercase)
   │  • find user by email; ACTIVE + password hash present?
   │  • BCrypt verify (dummy-hash compare on failure → no enumeration/timing leak)
   ▼
JwtTokenService (security module) issues access token (sub, role, iss, aud, iat, exp)
   │  + opaque refresh token (SHA-256 hash stored in refresh_tokens)
   ▼
Client stores tokens; sends  Authorization: Bearer <access JWT>
   ▼
Spring Security filter chain
   ▼
JwtDecoder validates: signature (HMAC secret local/test, RSA public key prod),
   │  expiry, issuer, audience
   ▼
JwtAuthenticationToken (principal = Jwt)
   │  RoleJwtAuthenticationConverter: role claim → ROLE_<role> authority (+ scope → SCOPE_*)
   ▼
authorizeHttpRequests (SecurityConfig): URL + role rules
   │  ── no/invalid token ─────────────────► RestAuthenticationEntryPoint → 401 JSON
   │  ── missing authority ────────────────► RestAccessDeniedHandler  → 403 JSON
   ▼
Controller  (maps principal → CurrentUser via CurrentUserProvider)
   ▼
Application use case  (takes CurrentUser as a plain parameter)
   ▼
Domain  (sees no security types at all)
```

## 2. Authentication vs Authorization

- **Authentication** ("who are you?") — proving the access token is valid. Done by the
  Resource Server: signature, expiry, issuer, audience.
- **Authorization** ("what may you do?") — checking the caller's `ROLE_*` authorities against
  the endpoint rules. Done by `authorizeHttpRequests`.
- The two failures are distinct and handled separately: **401** (unauthenticated) vs
  **403** (authenticated but not allowed). Two dedicated handlers
  (`RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`) keep the distinction explicit.

## 3. Token lifecycle

### 3.1 Login — `POST /api/v1/auth/login`

Body: `{"email": "...", "password": "..."}`. On success returns:

```json
{ "accessToken": "<JWT>", "refreshToken": "<opaque>", "tokenType": "Bearer", "expiresIn": 900 }
```

- Access token: JWT, default TTL **15m** (`app.security.jwt.access-token-ttl`).
- Refresh token: opaque (32 random bytes, base64url); only its **SHA-256 hash** is stored in
  the `refresh_tokens` table. Default TTL **7d** (`app.security.jwt.refresh-token-ttl`, absolute).
- All failure cases (unknown email, wrong password, inactive account, legacy account without
  a password) return the same `401 INVALID_CREDENTIALS` and run a dummy BCrypt comparison, so
  callers cannot enumerate accounts or measure account state through timing.

### 3.2 Refresh — `POST /api/v1/auth/refresh`

Body: `{"refreshToken": "..."}`. **Rotates** the token: the presented token is atomically
consumed (conditional update `SET revoked_at = now WHERE token_hash = ? AND revoked_at IS NULL
AND expires_at > now`) and a successor refresh token plus a fresh access token are issued in
the **same transaction**. A consumed/expired/unknown token, or an inactive user, returns
`401 INVALID_REFRESH_TOKEN`. Concurrent refreshes with the same token serialize on the atomic
consume — exactly one caller wins.

### 3.3 Logout — `POST /api/v1/auth/logout`

Authenticated (access token in the header). Body: `{"refreshToken": "..."}`. Revokes the
refresh token if it belongs to the authenticated user; idempotent (204). **Access tokens
remain valid until `exp`** — this is the documented stateless-JWT trade-off. To invalidate
access tokens immediately, rotate the signing keys (see §8.3).

## 4. JWT validation details

| Mode | Configuration | Validated |
|---|---|---|
| Local / test | `app.security.jwt.secret-key` (HMAC HS256) | signature, expiry |
| Production | `app.security.jwt.public-key` (RSA) | signature, expiry, **issuer**, **audience** |

The two modes are **mutually exclusive**: `SecurityModeValidator` fails startup if both are
configured, neither is configured, or only one half of the RSA pair is present. The HMAC
decoder (`LocalJwtDecoderConfig`) is active only when `secret-key` is set; the RSA decoder
(`RsaJwtDecoderConfig`) only when `public-key` is set. In RSA mode `JwtTokenService` performs
a startup self-check (signs a throwaway token and validates it with the public key) to prove
the key pair matches.

## 5. RBAC — roles and authorities

The access token carries a single `role` claim (`ADMIN` or `USER`). `RoleJwtAuthenticationConverter`
maps it to `ROLE_<role>` using a closed allow-list; a missing/unknown role yields no `ROLE_*`
authority (403 on role-gated endpoints), and a malformed (non-string) role claim fails
authentication (401). The default `scope` → `SCOPE_*` mapping is kept for compatibility.

| Endpoint | Rule |
|---|---|
| `GET /api/v1/activities/**` | `authenticated()` |
| `POST /api/v1/activities` | `hasAnyRole("USER","ADMIN")` |
| `PUT /api/v1/activities/{id}` | `hasAnyRole("USER","ADMIN")` |
| `DELETE /api/v1/activities/{id}` | `hasRole("ADMIN")` |
| `GET /api/v1/workflow-entries/**` | `authenticated()` |
| `POST /api/v1/users` | `hasRole("ADMIN")` |
| `GET /api/v1/users/**` | `authenticated()` |
| `POST /api/v1/auth/login`, `/api/v1/auth/refresh` | `permitAll()` |
| `POST /api/v1/auth/logout` | `authenticated()` |
| `/actuator/health`, `/actuator/info` | `permitAll()` |
| `/actuator/prometheus` | `hasAuthority("SCOPE_prometheus")` — dedicated scraper token only (see docs/observability.md) |
| `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui/**`, `/swagger-ui.html` | `permitAll()` — OpenAPI docs; **disabled in prod** (`springdoc.*.enabled=false`) |
| everything else | `authenticated()` |

The fallback `authenticated()` requires authentication; role rules are added per endpoint in
this reviewed matrix.

## 6. 401 vs 403

```json
// 401
{ "code": "UNAUTHORIZED", "message": "Authentication is required", "timestamp": "...", "path": "/api/v1/activities" }
// 403
{ "code": "FORBIDDEN", "message": "You do not have permission to access this resource", "timestamp": "...", "path": "/api/v1/activities" }
```

Both use the same `ApiError` contract as every other error. No internal security details
are exposed.

## 7. CORS

Browser clients (SPAs) call the API cross-origin. The allowed origins are configured in
`app.security.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`, comma-separated; default
`http://localhost:3000` for local development):

```yaml
app:
  security:
    cors:
      allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000,https://app.example.com}
```

- Methods: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`; allowed headers `*`; preflight cache
  `maxAge` 3600s.
- **`allowCredentials(false)` is deliberate**: authentication uses a JWT bearer header, never
  cookies, so credentials-based requests — which would require exact origin matching and make
  wildcard headers impossible — are not supported.
- Preflights (OPTIONS) are answered by Spring Security's `CorsFilter` before any limiting layer
  and never consume rate-limit permits.

## 8. Rate limiting and throttling

Two per-client-IP layers protect the API, backed by **Redis fixed-window counters**
(`RedisFixedWindowRateLimiter`, atomic Lua `INCR`+`EXPIRE`). The request thread is never
blocked; over-limit requests get an immediate 429:

| Layer | Window | Default | 429 `code` |
|---|---|---|---|
| `ThrottleFilter` (burst) | `throttle.limit-refresh-period` | 20 req / 1s | `THROTTLED` |
| `RateLimitFilter` (quota) | `rate-limit.limit-refresh-period` | 100 req / 1m | `RATE_LIMITED` |

Chain order: CORS → throttle → rate limit → authentication. The shortest window fails first.
The layers sit before authentication, so anonymous endpoints (login, refresh) are covered too.
Both are disabled in the test profile (`enabled: false`) — a disabled layer is absent from the
filter chain entirely.

```yaml
app:
  security:
    throttle:
      enabled: true
      limit-for-period: ${THROTTLE_LIMIT:20}
      limit-refresh-period: 1s
    rate-limit:
      enabled: true
      limit-for-period: ${RATE_LIMIT_MAX:100}
      limit-refresh-period: 1m
    limiter:
      redis-fail-open: ${LIMITER_REDIS_FAIL_OPEN:true}
```

- **Distributed by construction**: each window is a Redis key `app:limit:{layer}:{ip}` with a
  TTL equal to the window. The key expires with the window — no idle-eviction sweep is needed —
  and limits hold across instances (a horizontally scaled deployment shares one Redis).
- **429 contract**: shared `ApiError` (`code=RATE_LIMITED` / `THROTTLED`, RFC 6585
  `Retry-After: <refresh seconds>` header):
  ```json
  { "code": "RATE_LIMITED", "message": "Rate limit exceeded, please retry later", "timestamp": "...", "path": "/api/v1/activities" }
  ```
- **Actuator exclusion**: `/actuator/**` never passes through the limiters — health probes and
  the Prometheus scraper are never limited.
- **Fail-open on Redis outage** (availability > strict limiting): a connection failure or
  command timeout (`RedisSystemException`, raised by the configured
  `spring.data.redis.timeout` when Redis is slow or hung) logs a WARN, increments the
  `app.security.limiter.failopen` meter (tag `layer`) and **allows** the request. Set
  `app.security.limiter.redis-fail-open=false` to fail closed (500) instead. In production the
  trade-off is deliberate: a silent rate-limit loss is the price of availability — **alert on
  the failopen meter** so the degradation is visible.
- **Command timeout**: `spring.data.redis.timeout` / `connect-timeout` (default 500ms) turn a
  hung/slow Redis into a fail-open event instead of a blocked request thread.
- **Deployment assumptions**: the limiter keys on `request.getRemoteAddr()` — a trusted
  reverse proxy in front collapses every client into one IP (the shared budget then applies to
  everyone). Either terminate the proxy before the app or move limiting to the gateway. Scale-out
  beyond a single Redis instance requires Redis cluster/sentinel.
- **Fixed-window property**: a fixed window allows up to 2× the limit at window edges (classic
  fixed-window behavior) — accepted for this design.
- **429 counts**: observable via the request-log `http.response.status_code` / `event.outcome`
  fields (the per-IP Resilience4j meters are gone with the in-memory limiters).

## 9. Resilience4j fault tolerance

`resilience4j-spring-boot4` (v2.4.0, the Spring Boot 4 starter) is on the classpath with
`spring-boot-starter-aspectj` (annotation support).

- **Not used today — shipped as library + config**: Retry / CircuitBreaker / TimeLimiter /
  Bulkhead. The application makes zero outbound HTTP calls today, so no `@Retry`/`@CircuitBreaker`
  annotations exist — nothing speculative. (The per-IP `RateLimiter` usage was replaced by the
  Redis fixed-window counters in §8.) When the first outbound integration lands, apply the
  standard patterns on the integration client:
  - `@Retry` for transient failures (5xx, timeouts) on idempotent calls;
  - `@CircuitBreaker` around retries so a failing dependency does not pile up load;
  - `@TimeLimiter` (async) for hard deadlines;
  - `@Bulkhead` to bound concurrent in-flight calls to a dependency.
  - Configure instances under `resilience4j.*` (e.g. `resilience4j.retry.instances.*`,
    `resilience4j.circuitbreaker.instances.*`); the starter auto-configures the registries,
    actuator health indicators and (unless disabled) meters.

## 10. Local development

Security is **never disabled** — not even locally. Local mode uses the HMAC secret, so tokens
are still signature-validated. To get a token:

```bash
# mint an HS256 JWT signed with the local secret (development convenience only)
python scripts/mint-local-jwt.py --sub <user-id> --role ADMIN
```

- The `sub` must be a user id that exists in the `users` table.
- Default secret matches `application-local.yml`; override with `--secret` / `JWT_LOCAL_SECRET`.
- The script is stdlib-only Python; it is not part of the application and must never be used
  against production.

To exercise the real login flow locally, create a user with a password (requires `ROLE_ADMIN`)
and call `/api/v1/auth/login`. On a fresh database, bootstrap the first admin:

```bash
export BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD='change-me'
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## 11. Production

### 8.1 RSA key pair

Generate a key pair and provide both PEM values:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
export JWT_PRIVATE_KEY="$(cat private.pem)" JWT_PUBLIC_KEY="$(cat public.pem)"
```

### 8.2 Bootstrap

```bash
export DB_URL=... DB_USERNAME=... DB_PASSWORD=...
export JWT_PRIVATE_KEY="$(cat private.pem)" JWT_PUBLIC_KEY="$(cat public.pem)"
export BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD='change-me'
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

`FirstAdminBootstrap` creates the first `ADMIN` user only when the email is not already taken
(idempotent). Legacy users upgraded from a pre-credential schema have a null password hash and
cannot log in until an operator sets one (see §9).

### 8.3 Cutover and rollback

Switching signing trust invalidates existing sessions. Procedure:
1. Generate a new key pair; deploy with the new keys.
2. Revoke all refresh tokens (`UPDATE refresh_tokens SET revoked_at = now()`), forcing clients
   to re-login.
3. Rollback: redeploy with the previous keys and re-issue. Access tokens signed with the old
   key are rejected once the decoder uses the new public key.

## 12. Legacy users and password recovery

- Users created before this feature have a **null** `password_hash` and role `USER`. They
  cannot log in (uniform 401) until an operator sets a password.
- Operational workaround (no self-service reset in scope): generate a BCrypt hash with
  `python scripts/hash-password.py <password>` and update the row:
  `UPDATE users SET password_hash = '<hash>' WHERE email = '<email>';`
- Password change/reset flows, rate limiting, and authentication audit metrics are **out of
  scope** for this iteration (see §11).

## 13. Why security does not belong in the domain layer

Domain invariants and business rules should hold regardless of transport or caller. If domain
code read `SecurityContext` directly:

- it would be untestable without a security context;
- it would couple business logic to a framework;
- the same domain could not be reused by a batch job, a message consumer or a test.

The rule: security types (`Jwt`, `Authentication`, `SecurityContext`, `HttpServletRequest`)
appear **only** in the `security` module and at the API boundary. Use cases receive
`CurrentUser` — a plain record — as a parameter. The user module's application layer depends
on user-owned ports (`PasswordHasher`, `AccessTokenIssuer`); the security-module coupling is
confined to `infrastructure/security` adapters. The architecture tests enforce the direction
by construction.

## 14. Documented limitations (out of scope)

- **Account lockout / per-user limiting**: only per-IP limiting exists (see §8). Per-user limits
  are possible later (the login endpoints have no user identity until authenticated); lockout on
  repeated failed logins is not implemented. Limiting is distributed via Redis (see §8) — a
  scaled-out deployment shares one limiter store.
- **Authentication audit metrics**: login success/failure counters exist
  (`app.auth.logins`, see docs/observability.md); no per-user audit trail or alerting.
- **Password change / forgot-password / reset flows**: not implemented; see §9 workaround.
- **Refresh-token family / reuse detection**: not implemented; rotation + revocation only.
- **Cookie transport**: refresh tokens are returned in JSON for bearer clients; browser apps
  should store them in `HttpOnly`/`Secure`/`SameSite` cookies (requires CSRF protection).
- **Session caps**: no per-user session limit; each login creates a new refresh token.
