# Security

## 1. The full flow

```text
Client
   │  Authorization: Bearer <JWT>            (obtained from the external Identity Provider)
   ▼
Spring Security filter chain
   │
   ▼
OAuth2 Resource Server (BearerTokenAuthenticationFilter)
   │  JwtDecoder validates the token:
   │    • signature (JWK set from OIDC discovery, or HMAC secret in local/test)
   │    • expiration (nbf/exp, 60s clock skew)
   │    • issuer (iss matches the configured issuer-uri, in issuer mode)
   ▼
JwtAuthenticationToken (principal = Jwt)
   │  default JwtGrantedAuthoritiesConverter maps scope/scp claims
   │  → authorities "SCOPE_activity:write", ...
   ▼
authorizeHttpRequests (SecurityConfig): URL + scope rules
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

- **Authentication** ("who are you?") — proving the JWT is valid. Done by the Resource
  Server: signature, expiry, issuer.
- **Authorization** ("what may you do?") — checking the caller's authorities against the
  endpoint rules. Done by `authorizeHttpRequests`.
- The two failures are distinct and handled separately: **401** (unauthenticated) vs
  **403** (authenticated but not allowed). The template keeps two dedicated handlers
  (`RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`) so the distinction stays
  explicit.

## 3. JWT validation details

| Mode | Configuration | Validated |
|---|---|---|
| Production | `spring.security.oauth2.resourceserver.jwt.issuer-uri=${JWT_ISSUER_URI}` | signature (via OIDC discovery of the IdP's JWK set), expiry, issuer, audience (if configured) |
| Local / test | `app.security.jwt.secret-key` (HMAC HS256) | signature, expiry — **no issuer validation** (documented dev trade-off) |

The HMAC decoder bean is created by `LocalJwtDecoderConfig` **only when** the property is
present (`@ConditionalOnProperty`). Production never sets it — the property only exists in
`application-local.yml` and `application-test.yml`, so the production profile is *impossible*
to accidentally run in HMAC mode. In production, omitting `JWT_ISSUER_URI` fails startup
(fail-fast, no silent unauthenticated mode).

## 4. Scopes / authorities

The standard JWT `scope` (or `scp`) claim is mapped by Spring Security's default converter
to authorities prefixed with `SCOPE_`. Example JWT:

```json
{
  "sub": "user-123",
  "scope": "activity:read activity:write activity:admin",
  "iss": "https://idp.example.com/realms/acme",
  "exp": 1893456000
}
```

→ authorities: `SCOPE_activity:read`, `SCOPE_activity:write`, `SCOPE_activity:admin`.

| Endpoint | Rule |
|---|---|
| `GET /api/activities/**` | `authenticated()` |
| `POST /api/activities` | `hasAuthority("SCOPE_activity:write")` |
| `PUT /api/activities/{id}` | `hasAuthority("SCOPE_activity:write")` |
| `DELETE /api/activities/{id}` | `hasAuthority("SCOPE_activity:admin")` |
| `GET /api/workflow-entries/**` | `authenticated()` |
| `POST /api/users` | `hasAuthority("SCOPE_user:write")` |
| `GET /api/users/**` | `authenticated()` |
| `/actuator/health`, `/actuator/info` | `permitAll()` |
| everything else | `authenticated()` |

Using standard claims (`scope`/`scp`) keeps the application **provider-agnostic**: any
standards-compliant OAuth2/OIDC provider can issue these tokens without proprietary claim
mapping. If a provider only exposes `roles`, add a `JwtAuthenticationConverter` mapping
them — that is a security-module change, not a domain change.

## 5. 401 vs 403

```json
// 401
{ "code": "UNAUTHORIZED", "message": "Authentication is required", "timestamp": "...", "path": "/api/activities" }
// 403
{ "code": "FORBIDDEN", "message": "You do not have permission to access this resource", "timestamp": "...", "path": "/api/activities" }
```

Both use the same `ApiError` contract as every other error. No internal security details
are exposed.

## 6. Local development

Security is **never disabled** — not even locally. Local mode uses the HMAC secret decoder,
so tokens are still signature-validated. To get a token:

```bash
# mint an HS256 JWT signed with the local secret (development convenience only)
python scripts/mint-local-jwt.py --sub <user-id> --scope "activity:read activity:write activity:admin user:write"
```

- The `sub` must be a user id that exists in the `users` table (create one via
  `POST /api/users` first).
- Default secret matches `application-local.yml`; override with `--secret` /
  `JWT_LOCAL_SECRET` if you changed it.
- The script is stdlib-only Python; it is not part of the application and must never be
  used against production.

## 7. Testing with mocked JWTs

Integration tests use Spring Security Test's `jwt()` request post-processor, which injects a
valid `JwtAuthenticationToken` with the given claims through the real filter chain — no
network, no IdP:

```java
mockMvc.perform(post("/api/activities")
        .with(jwt().jwt(j -> j.subject(userId).claim("scope", "activity:write")))
        ...);
```

Additionally, `JwtValidationIntegrationTest` exercises the **real decoder**: it mints actual
HS256 tokens with nimbus-jose using the test secret and asserts: valid → 201,
wrong key → 401, expired → 401, garbage → 401.

## 8. Production Identity Provider configuration

```bash
export DB_URL=jdbc:postgresql://... DB_USERNAME=... DB_PASSWORD=...
export JWT_ISSUER_URI=https://keycloak.example.com/realms/your-realm   # or Auth0/Cognito/Okta issuer URL
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

The issuer URL must be the OIDC issuer (`/.well-known/openid-configuration` must resolve).
Spring Security fetches the JWK set from the IdP and validates signatures/expiry/issuer
automatically. Client credentials / auth-code flows happen entirely at the IdP; the
application only consumes the resulting access tokens.

## 9. Why authentication is externalized

- The application never stores credentials → no password hashing, no reset flows, no
  breach blast radius.
- MFA, SSO, social login, session revocation, key rotation: all delegated to the IdP.
- The same IdP can serve many services of the company.
- The user module stays a *business* module (profile data), decoupled from identity
  mechanics.

## 10. Why security does not belong in the domain layer

Domain invariants and business rules should hold regardless of transport or caller. If
domain code read `SecurityContext` directly:

- it would be untestable without a security context;
- it would couple business logic to a framework;
- the same domain could not be reused by a batch job, a message consumer or a test.

The template's rule: security types (`Jwt`, `Authentication`, `SecurityContext`,
`HttpServletRequest`) appear **only** in the `security` module and at the API boundary.
Use cases receive `CurrentUser` — a plain record — as a parameter. The architecture tests
enforce the direction by construction: the domain packages contain no security imports,
and Modulith verification would flag any module reaching into another module's internals.
