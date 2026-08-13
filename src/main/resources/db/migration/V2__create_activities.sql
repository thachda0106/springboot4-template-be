-- Activity bounded context.
-- created_by references users(id): a deliberate single-database coupling.
-- Trade-off documented in docs/module-boundaries.md (extraction would drop the FK).
CREATE TABLE activities (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    status      VARCHAR(20)  NOT NULL,
    created_by  UUID         NOT NULL REFERENCES users (id),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_activities_created_by ON activities (created_by);
