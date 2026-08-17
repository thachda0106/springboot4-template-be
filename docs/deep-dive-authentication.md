# Deep Dive: First-Party Authentication (Login/Logout + RBAC)

How the modular monolith authenticates users with email + password, issues its own JWTs,
rotates refresh tokens, revokes sessions on logout, and enforces role-based access.

---

## 1. Overview

| Name | Path | Role |
|---|---|---|
| `AuthController` | `user/api/AuthController.java` | REST endpoints: login, refresh, logout |
| `LoginUseCase` | `user/application/LoginUseCase.java` | Verifies email+password, issues a token pair |
| `RefreshTokenUseCase` | `user/application/RefreshTokenUseCase.java` | Atomically rotates a refresh token |
| `LogoutUseCase` | `user/application/LogoutUseCase.java` | Revokes a refresh token (idempotent) |
| `CreateUserUseCase` | `user/application/CreateUserUseCase.java` | Creates a user with password + role |
| `FirstAdminBootstrap` | `user/application/FirstAdminBootstrap.java` | Provisions the first ADMIN on startup |
| `RefreshTokenFactory` | `user/application/RefreshTokenFactory.java` | Generates opaque tokens + SHA-256 hashes |
| `RefreshTokenPolicy` | `user/application/RefreshTokenPolicy.java` | Owns the refresh-token lifetime |
| `PasswordRules` | `user/application/PasswordRules.java` | Enforces the 72-byte BCrypt limit |
| `PasswordHasher` (port) | `user/application/port/PasswordHasher.java` | App-layer password-hashing contract |
| `AccessTokenIssuer` (port) | `user/application/port/AccessTokenIssuer.java` | App-layer token-issuance contract |
| `PasswordHasherAdapter` | `user/infrastructure/security/PasswordHasherAdapter.java` | Wraps the security `PasswordEncoder` |
| `AccessTokenIssuerAdapter` | `user/infrastructure/security/AccessTokenIssuerAdapter.java` | Wraps the security `JwtTokenService` |
| `User` (domain) | `user/domain/model/User.java` | User aggregate with `passwordHash` + `role` |
| `RefreshToken` (domain) | `user/domain/model/RefreshToken.java` | Refresh-token session aggregate |
| `RefreshTokenRepository` | `user/domain/repository/RefreshTokenRepository.java` | Domain contract incl. `consumeIfValid` |
| `SpringDataRefreshTokenRepository` | `user/infrastructure/persistence/SpringDataRefreshTokenRepository.java` | JPA repo with the atomic consume query |
| `JwtTokenService` | `security/JwtTokenService.java` | Signs access tokens (HS256 / RS256) |
| `RoleJwtAuthenticationConverter` | `security/RoleJwtAuthenticationConverter.java` | Maps `role` claim → `ROLE_*` authority |
| `SecurityConfig` | `security/SecurityConfig.java` | Filter chain + role-based rules |
| `LocalJwtDecoderConfig` | `security/LocalJwtDecoderConfig.java` | HMAC decoder (local/test) |
| `RsaJwtDecoderConfig` | `security/RsaJwtDecoderConfig.java` | RSA decoder (prod) |
| `SecurityModeValidator` | `security/SecurityModeValidator.java` | Enforces exactly one key mode |
| `V4__add_credentials_and_refresh_tokens.sql` | `db/migration/V4__...sql` | Schema: `password_hash`, `role`, `refresh_tokens` |

---

## 2. Declarative knowledge

### 2.1 The problem: a stateless JWT cannot be revoked

```text
                    ┌──────────────────────────────────────────────────────┐
                    │  Why two tokens? A stateless JWT cannot be revoked   │
                    └──────────────────────────────────────────────────────┘

  Access token (JWT, 15 min)          Refresh token (opaque, 7 days)
  ┌──────────────────────────┐        ┌──────────────────────────────┐
  │ sub: 8f1c2e4a-...        │        │ 3f9aK2... (32 random bytes)  │
  │ role: ADMIN              │        │ stored as SHA-256 hash in    │
  │ exp: 15 min              │        │ the refresh_tokens table     │
  └──────────────────────────┘        └──────────────────────────────┘
        │ sent on every request              │ used only to get a new pair
        ▼                                    ▼
  Validated by the JwtDecoder          Validated by DB lookup
  (signature, exp, iss, aud)          (hash match, not revoked, not expired)
        │                                    │
        └────────────── THE GAP ────────────┘
   A stolen access token works until exp (15 min).
   Logout can only revoke the refresh token; the access
   token keeps working until it expires. This is the
   documented stateless-JWT trade-off (docs/security.md §3.3).
```

The refresh token exists precisely because the access token cannot be revoked: when the
refresh token is revoked, the client can no longer obtain new access tokens, so the session
dies at the latest when the current access token expires.

### 2.2 Core variables

| Variable | Type | Plain-English meaning |
|---|---|---|
| `rawPassword` | `String` | The password as typed by the client; never stored |
| `passwordHash` | `String` | The BCrypt hash stored on `User`; the only credential at rest |
| `normalizedEmail` | `String` | `email.trim().toLowerCase()` — the canonical lookup key |
| `DUMMY_HASH` | `String` | A valid BCrypt hash compared against when the account is unknown/inactive/null-hash, to equalize timing |
| `now` | `Instant` | `clock.instant()` — one time source per use case, injectable for tests |
| `rawToken` | `String` | The opaque refresh token (32 random bytes, base64url) returned to the client exactly once |
| `tokenHash` | `String` | SHA-256 hex digest of `rawToken`; the persisted lookup key |
| `expiresAt` | `Instant` | `now + refresh-token-ttl` (absolute, default 7 days) |
| `accessTokenTtl` | `Duration` | Access-token lifetime (default 15 minutes) |
| `refreshTokenTtl` | `Duration` | Refresh-token lifetime (default 7 days) |
| `issuer` | `String` | The `iss` claim value (default `modular-monolith`) |
| `audience` | `String` | The `aud` claim value (default `modular-monolith`) |
| `kid` | `String` | Stable RSA key id derived from the public-key modulus |
| `role` | `String` / `UserRole` | The JWT claim / domain enum; maps to `ROLE_<role>` |
| `KNOWN_ROLES` | `Set<String>` | Closed allow-list `{ADMIN, USER}` |

### 2.3 Key concepts

| Term | Definition |
|---|---|
| Access token | Short-lived JWT sent on every request; validated by the decoder |
| Refresh token | Long-lived opaque token used only to obtain a new token pair |
| Rotation | On refresh, the old token is revoked and a successor is issued |
| Revocation | Marking a refresh token `revoked_at` so it can no longer be used |
| Atomic consume | A single conditional `UPDATE` that revokes a token only if still valid |
| RBAC | Role-based access control: `role` → `ROLE_*` authority → endpoint rule |
| BCrypt | Adaptive password hash; hard 72-byte input limit |
| HMAC mode | Local/test signing with a shared secret (`HS256`) |
| RSA mode | Production signing with a private key, validation with the public key (`RS256`) |
| Dummy-hash compare | Running BCrypt against a constant hash so failures take the same time as successes |
| Port | An application-layer interface (e.g. `PasswordHasher`) implemented by an adapter |

---

## 3. Data structures

### 3.1 Domain models

```text
type User = {
  id:           UserId      // UUID value object
  name:         String      // display name, trimmed
  email:        String      // canonical: trimmed + lowercased
  status:       UserStatus  // ACTIVE | INACTIVE
  passwordHash: String      // BCrypt hash; null for legacy rows (cannot log in)
  role:         UserRole    // ADMIN | USER
  createdAt:    Instant
  updatedAt:    Instant
}
// Factories: User.create(name, email, passwordHash, role)  (validates, User.java:39-54)
//            User.restore(...)                              (persistence, User.java:57-60)

type UserRole = ADMIN | USER          // closed set (UserRole.java)
type UserStatus = ACTIVE | INACTIVE   // lifecycle state

type RefreshToken = {
  id:         RefreshTokenId  // UUID value object
  userId:     UserId          // owning user
  tokenHash:  String          // SHA-256 of the opaque token (never the raw token)
  expiresAt:  Instant         // absolute expiry
  revokedAt:  Instant | null  // set once on revoke/consume
  createdAt:  Instant
}
// Factories: RefreshToken.issue(userId, tokenHash, expiresAt)  (RefreshToken.java:32-34)
//            RefreshToken.restore(...)                         (RefreshToken.java:37-40)
// Methods:   revoke()  (idempotent, RefreshToken.java:43-47)
//            isValid(now) = revokedAt == null && now < expiresAt  (RefreshToken.java:50-52)
```

### 3.2 API DTOs

```text
type LoginRequest = {
  email:    String  // @NotBlank @Email @Size(max=320)
  password: String  // @NotBlank @Size(max=72)  — byte limit enforced in use case
}

type RefreshTokenRequest = {
  refreshToken: String  // @NotBlank
}

type AuthResponse = {
  accessToken:  String  // the JWT
  refreshToken: String  // the opaque refresh token
  tokenType:    String  // always "Bearer"
  expiresIn:    long    // access-token TTL in seconds (900 for 15m)
}

type AuthResult = {          // application-layer result (not a DTO)
  accessToken:      String
  refreshToken:     String
  expiresInSeconds: long
}

type CreateUserRequest = {
  name:     String    // @NotBlank @Size(max=200)
  email:    String    // @NotBlank @Email @Size(max=320)
  password: String    // @NotBlank @Size(min=8, max=72)
  role:     UserRole  // optional; defaults to USER
}
```

### 3.3 Database (V4 migration)

```sql
-- users (altered)
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);          -- nullable (legacy rows)
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
    CHECK (role IN ('ADMIN', 'USER'));                            -- DB-level role invariant

-- refresh_tokens (new)
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex; UNIQUE backs the rotation race
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,                   -- NULL until revoked/consumed
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
```

### 3.4 Access-token JWT claims

```text
type AccessTokenClaims = {
  sub:  String   // user id (UUID as string)
  role: String   // "ADMIN" | "USER"
  iss:  String   // issuer (default "modular-monolith")
  aud:  [String] // audience list (default ["modular-monolith"])
  iat:  Instant  // issued at
  exp:  Instant  // iat + accessTokenTtl
}
// Header: { alg: "HS256" | "RS256", kid: "<stable-id>" (RSA only) }
```

---

## 4. Algorithm diagrams

### 4.1 Architecture

```text
 HTTP request
    │
    ▼
┌────────────────────────────── security module ──────────────────────────────┐
│ SecurityConfig (filter chain)                                               │
│   │  JwtDecoder (LocalJwtDecoderConfig | RsaJwtDecoderConfig)               │
│   │    → validates signature, exp, iss, aud                                 │
│   │  RoleJwtAuthenticationConverter → ROLE_<role> authority                 │
│   │  authorizeHttpRequests → role rules (SecurityConfig.java:45-56)         │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────── user module ──────────────────────────────────┐
│ AuthController ──► LoginUseCase ──► UserRepository ──► UserRepositoryAdapter │
│                       │          └─► RefreshTokenRepository ──► Adapter     │
│                       │          └─► PasswordHasher (port) ──► Adapter ──►  │
│                       │          └─► AccessTokenIssuer (port) ──► Adapter   │
│                       │          └─► RefreshTokenFactory + Policy + Clock   │
│                       └─► RefreshTokenUseCase / LogoutUseCase (same deps)   │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────── security module ──────────────────────────────┐
│ JwtTokenService (signs access tokens)   PasswordEncoder (BCrypt)            │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Login formula

```text
input:  email = "  Alice@Example.com ", rawPassword = "s3cret-pass"
        ↓
step 1  normalizedEmail = "alice@example.com"            (LoginUseCase.java:53)
step 2  user = findByEmail("alice@example.com")          (LoginUseCase.java:56)
step 3  guard: user == null OR status != ACTIVE OR passwordHash == null
            → matches(rawPassword, DUMMY_HASH) then 401  (LoginUseCase.java:57-60)
step 4  guard: !matches("s3cret-pass", user.passwordHash)
            → 401                                        (LoginUseCase.java:61-63)
step 5  accessToken = issueAccessToken(userId, "USER")   (LoginUseCase.java:65)
step 6  rawToken = 32 random bytes base64url             (RefreshTokenFactory.java:43-47)
        tokenHash = SHA-256(rawToken)                    (RefreshTokenFactory.java:34-41)
        expiresAt = now + 7d                             (RefreshTokenPolicy.java:26-28)
        save RefreshToken(userId, tokenHash, expiresAt)  (LoginUseCase.java:66-68)
        ↓
output: AuthResult(accessToken, rawToken, expiresInSeconds = 900)
```

### 4.3 Refresh formula (rotation)

```text
input:  rawToken = "3f9aK2..." (from the client)
        ↓
step 1  tokenHash = SHA-256("3f9aK2...")                 (RefreshTokenUseCase.java:45)
step 2  presented = findByTokenHash(tokenHash)           (RefreshTokenUseCase.java:47-48)
            → absent → 401 INVALID_REFRESH_TOKEN
step 3  won = consumeIfValid(tokenHash, now)             (RefreshTokenUseCase.java:51)
            → single UPDATE ... WHERE revoked_at IS NULL AND expires_at > now
            → false (already consumed/expired) → 401     (RefreshTokenUseCase.java:52-53)
step 4  user = findById(presented.userId)                (RefreshTokenUseCase.java:55-56)
            → absent → 401
        guard: user.status != ACTIVE → 401               (RefreshTokenUseCase.java:57-59)
step 5  new access token + successor refresh token       (RefreshTokenUseCase.java:61-64)
            (same transaction as step 3 — both commit or both roll back)
        ↓
output: AuthResult(newAccessToken, newRawToken, 900)
```

### 4.4 Mode comparison

| | HMAC (local/test) | RSA (prod) |
|---|---|---|
| Config | `app.security.jwt.secret-key` | `app.security.jwt.private-key` + `public-key` |
| Algorithm | HS256 | RS256 |
| Signer | `ImmutableSecret` (`JwtTokenService.java:64-67`) | `ImmutableJWKSet` (`JwtTokenService.java:68-80`) |
| Decoder | `LocalJwtDecoderConfig` (`@ConditionalOnProperty secret-key`) | `RsaJwtDecoderConfig` (`@ConditionalOnProperty public-key`) |
| Validated | signature, exp, iss, aud | signature, exp, iss, aud |
| Startup check | — | key-pair self-check (`JwtTokenService.java:105-114`) |
| Enforced by | `SecurityModeValidator` (exactly one mode, `SecurityModeValidator.java:22-35`) | same |

### 4.5 RBAC mapping

```text
JWT role claim ──► RoleJwtAuthenticationConverter ──► authority ──► endpoint rule
  "ADMIN"      ──► ROLE_ADMIN                    ──► DELETE /activities, POST /users
  "USER"       ──► ROLE_USER                     ──► POST/PUT /activities
  (missing)    ──► (none)                        ──► 403 on role-gated endpoints
  "SUPERUSER"  ──► (none, not in KNOWN_ROLES)    ──► 403
  (non-string) ──► BadJwtException → 401         ──► authentication fails
```

---

## 5. Event lifecycle — one full login

Concrete trace of `POST /api/v1/auth/login` for user `alice@example.com` / `s3cret-pass`:

```text
 1. Client sends:  POST /api/v1/auth/login
                   {"email": "alice@example.com", "password": "s3cret-pass"}
 2. SecurityConfig: /api/v1/auth/login is permitAll()          (SecurityConfig.java:47)
 3. AuthController.login(@Valid LoginRequest)                  (AuthController.java:40-43)
      → LoginUseCase.execute("alice@example.com", "s3cret-pass")
 4. LoginUseCase (tx begins):
      normalizedEmail = "alice@example.com"
      user = findByEmail → User{ id=8f1c2e4a-..., role=USER, passwordHash="$2a$10$..." }
      matches("s3cret-pass", hash) → true
 5. AccessTokenIssuer.issue("8f1c2e4a-...", "USER")
      → JwtTokenService.issueAccessToken → JWT:
        { sub: "8f1c2e4a-...", role: "USER", iss: "modular-monolith",
          aud: ["modular-monolith"], iat: ..., exp: now+15m }
 6. RefreshTokenFactory.issue(userId, now+7d)
      rawToken = "3f9aK2..." ; tokenHash = SHA-256("3f9aK2...")
      refreshTokenRepository.save(RefreshToken{...})   (row inserted)
 7. tx commits → AuthResult(accessToken, "3f9aK2...", 900)
 8. AuthResponse.from → 200 {"accessToken":"eyJ...","refreshToken":"3f9aK2...",
                             "tokenType":"Bearer","expiresIn":900}
```

---

## 6. Full-stack flow (swimlane)

```text
 User        HTTP / Security filter chain        AuthController      LoginUseCase        Ports/Adapters        JwtTokenService / DB
 ─────       ─────────────────────────────       ──────────────      ────────────        ──────────────        ─────────────────────
 POST /auth/login
   │  email+password
   │──► permitAll (SecurityConfig:47)
   │──► (no JWT needed for login)
   │──► AuthController.login
   │        │──► LoginUseCase.execute
   │        │        │──► findByEmail ────────────────► UserRepositoryAdapter ──► SELECT users
   │        │        │──► matches(raw, hash) ─────────► PasswordHasherAdapter ──► BCryptPasswordEncoder
   │        │        │──► issue(userId, role) ─────────► AccessTokenIssuerAdapter ─► JwtTokenService ──► sign JWT
   │        │        │──► issue(userId, expiresAt) ───► RefreshTokenFactory ──► hash + RefreshToken
   │        │        │──► save(token) ─────────────────► RefreshTokenRepositoryAdapter ──► INSERT refresh_tokens
   │        │        └──► AuthResult
   │        │──► AuthResponse.from
   │◄───────┘  200 {accessToken, refreshToken, expiresIn}
   │
   │  now call a protected API with the access token
   │──► Bearer eyJ... ──► JwtDecoder (signature/exp/iss/aud)
   │──► RoleJwtAuthenticationConverter → ROLE_USER
   │──► authorizeHttpRequests → allowed
   │──► Controller → Use Case → Domain
```

---

## 7. Design decisions

| Decision | Why X instead of Y | What breaks without it |
|---|---|---|
| App issues its own JWTs | The app was a pure Resource Server (external IdP). First-party login requires issuing tokens; HMAC local / RSA prod keeps it self-contained. | No way to log in with email+password at all |
| Refresh token stored as SHA-256 hash | A DB leak must not expose usable tokens; the hash is the lookup key. | A stolen DB yields working refresh tokens |
| Atomic `consumeIfValid` (single UPDATE) | Read-then-write lets two concurrent refreshes both succeed. The conditional UPDATE serializes them. | One refresh token can mint multiple successors (replay) |
| Ports `PasswordHasher`/`AccessTokenIssuer` | Keeps the user application layer free of Spring Security types (matches the `CurrentUser` port pattern). | App layer couples to security internals; harder to test/extract |
| Dummy-hash timing equalization | Unknown/inactive/null-hash accounts must take the same time as a real password check. | Account enumeration via response-time measurement |
| Byte-based BCrypt limit (72) | `@Size(max=72)` counts characters, not UTF-8 bytes; multibyte passwords could exceed BCrypt's limit and truncate into collisions. | Truncation collisions weaken passwords |
| Mutually exclusive HMAC/RSA + issuer/audience validation | Both modes must validate the same trust boundary; misconfiguration must fail startup. | Wrong-issuer tokens accepted; ambiguous startup |
| Logout = refresh revocation only | Stateless access tokens cannot be revoked without a denylist; the documented trade-off is that they live until `exp`. | (If you need instant access revocation, rotate signing keys — docs/security.md §8.3) |
| Single `role` column | Simplest RBAC; a roles table is over-engineering for two roles. | (If many roles/permissions are needed later, migrate to a join table) |
| `FirstAdminBootstrap` | A fresh deployment needs a first admin; config-driven + idempotent beats manual SQL. | Fresh deployments cannot create any user (POST /users needs ADMIN) |

---

## 8. Edge cases

| Scenario | How handled | Source |
|---|---|---|
| Unknown email | Dummy-hash compare + uniform 401 | `LoginUseCase.java:57-60` |
| Wrong password | 401 `INVALID_CREDENTIALS` | `LoginUseCase.java:61-63` |
| Inactive account | 401 on login and on refresh | `LoginUseCase.java:57`, `RefreshTokenUseCase.java:57-59` |
| Legacy row with null `password_hash` | Treated as invalid credentials (401), never 500 | `LoginUseCase.java:57` |
| Password > 72 UTF-8 bytes | 400 `INVALID_USER` (create + login) | `PasswordRules.java:17-21` |
| Oversized password (chars) | 400 `VALIDATION_ERROR` via `@Size(max=72)` | `LoginRequest.java:16` |
| Unknown/expired/revoked refresh token | 401 `INVALID_REFRESH_TOKEN` | `RefreshTokenUseCase.java:47-53` |
| Concurrent refresh with same token | Atomic consume → exactly one 200, one 401 | `SpringDataRefreshTokenRepository.java:25-33` |
| Logout of another user's token | Ownership filter → no-op (204) | `LogoutUseCase.java:33-35` |
| Logout without access token | 401 (endpoint is `authenticated()`) | `SecurityConfig.java:48` |
| Missing/unknown `role` claim | No `ROLE_*` authority → 403 on role-gated endpoints | `RoleJwtAuthenticationConverter.java:40-42` |
| Malformed (non-string) `role` claim | `BadJwtException` → 401 | `RoleJwtAuthenticationConverter.java:37-39` |
| Wrong issuer / audience | Rejected by decoder validators → 401 | `LocalJwtDecoderConfig.java:45-47`, `RsaJwtDecoderConfig.java:39-41` |
| Both/neither key mode configured | Startup fails fast | `SecurityModeValidator.java:22-35` |
| RSA key pair mismatch | Startup self-check fails | `JwtTokenService.java:105-114` |
| Bootstrap email already taken | Skip (idempotent) | `FirstAdminBootstrap.java:47-50` |
| Invalid role in `CreateUserRequest` | Jackson enum parse failure → 400 `MALFORMED_REQUEST` | `GlobalExceptionHandler` |

---

## 9. Integration point

The call site that ties the API layer to the application layer — `AuthController.login`:

```java
// AuthController.java:40-43
@PostMapping("/login")
public AuthResponse login(@Valid @RequestBody LoginRequest request) {
    return AuthResponse.from(
            loginUseCase.execute(
                    request.email(),    // raw email; trimmed+lowercased inside the use case
                    request.password()  // raw password; BCrypt-verified, never stored
            ));
}
```

The port adapter that ties the user module to the security module — `AccessTokenIssuerAdapter`:

```java
// AccessTokenIssuerAdapter.java
@Override
public String issue(String userId, String role) {
    return jwtTokenService.issueAccessToken(
            userId,  // the user's UUID as a string → JWT `sub`
            role     // "ADMIN" | "USER" → JWT `role` claim
    );
}
```

---

## 10. File map

```text
src/main/java/com/example/app/
├── security/
│   ├── JwtTokenService.java                 signs access tokens (HS256/RS256, kid, self-check)
│   ├── RoleJwtAuthenticationConverter.java  role claim → ROLE_* authority
│   ├── SecurityConfig.java                  filter chain + role-based rules
│   ├── LocalJwtDecoderConfig.java           HMAC decoder (local/test)
│   ├── RsaJwtDecoderConfig.java             RSA decoder (prod)
│   ├── SecurityModeValidator.java           enforces exactly one key mode
│   ├── PasswordEncoderConfig.java           BCrypt PasswordEncoder bean
│   ├── CurrentUserProvider.java             app-level current-user abstraction
│   └── package-info.java                    formalizes the module (leaf)
└── user/
    ├── api/
    │   ├── AuthController.java              login/refresh/logout endpoints
    │   ├── UserController.java              user CRUD (create now needs password+role)
    │   ├── UserApiExceptionHandler.java     401/409/400 mapping for auth + user errors
    │   └── dto/                             LoginRequest, RefreshTokenRequest, AuthResponse,
    │                                        CreateUserRequest, UserResponse
    ├── application/
    │   ├── LoginUseCase.java                verify credentials → token pair
    │   ├── RefreshTokenUseCase.java         atomic rotation
    │   ├── LogoutUseCase.java               revocation (ownership-checked)
    │   ├── CreateUserUseCase.java           create user with password + role
    │   ├── FirstAdminBootstrap.java         first ADMIN provisioning
    │   ├── RefreshTokenFactory.java         opaque token + SHA-256 hash
    │   ├── RefreshTokenPolicy.java          refresh TTL
    │   ├── PasswordRules.java               72-byte BCrypt limit
    │   ├── AuthResult.java                  application-layer result record
    │   ├── ClockConfig.java                 injectable Clock bean
    │   └── port/                            PasswordHasher, AccessTokenIssuer
    ├── domain/
    │   ├── model/                           User, UserRole, UserStatus, RefreshToken,
    │   │                                    RefreshTokenId, UserId
    │   ├── repository/                      UserRepository, RefreshTokenRepository
    │   └── exception/                       InvalidCredentialsException,
    │                                        InvalidRefreshTokenException, ...
    └── infrastructure/
        ├── security/                        PasswordHasherAdapter, AccessTokenIssuerAdapter
        └── persistence/                     UserJpaEntity, UserEntityMapper,
                                             RefreshTokenJpaEntity, RefreshTokenEntityMapper,
                                             SpringDataRefreshTokenRepository,
                                             RefreshTokenRepositoryAdapter
src/main/resources/db/migration/
└── V4__add_credentials_and_refresh_tokens.sql   password_hash, role, refresh_tokens + indexes
```
