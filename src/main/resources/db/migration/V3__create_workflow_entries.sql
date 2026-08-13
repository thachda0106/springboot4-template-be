-- Workflow bounded context: this module's view of activities, kept in sync via events.
-- activity_id UNIQUE: one workflow entry per activity.
-- ON DELETE CASCADE: deleting an activity removes its workflow entries in the
-- same transaction; the workflow listener is then a harmless no-op.
CREATE TABLE workflow_entries (
    id            UUID         PRIMARY KEY,
    activity_id   UUID         NOT NULL UNIQUE REFERENCES activities (id) ON DELETE CASCADE,
    activity_name VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
