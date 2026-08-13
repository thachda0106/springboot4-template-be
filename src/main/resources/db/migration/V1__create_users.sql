-- User bounded context: business user information only.
-- No credentials here: authentication is externalized to the Identity Provider.
CREATE TABLE users (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    email      VARCHAR(320) NOT NULL UNIQUE,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
