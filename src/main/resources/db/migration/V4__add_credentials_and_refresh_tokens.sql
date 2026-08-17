-- Credentials + RBAC for the user bounded context.
-- The application now issues its own JWTs (see docs/security.md): users carry a
-- BCrypt password hash and a single role. password_hash is nullable because
-- pre-existing rows have no password (they cannot log in until one is set);
-- new users always set it via CreateUserUseCase.
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
    CHECK (role IN ('ADMIN', 'USER'));

-- Refresh-token sessions: opaque tokens stored as SHA-256 hashes, with rotation
-- (revoke old, issue new) and revocation on logout. consumeIfValid performs an
-- atomic conditional update on revoked_at/expires_at.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
