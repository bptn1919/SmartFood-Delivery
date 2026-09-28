-- Mirrors ../../backend/users/models.py (CustomUser, AuthenticateToken) —
-- field list/constraints ported faithfully; physical naming is idiomatic
-- Java/Postgres, not Django's default table names (decided with the user,
-- see CLAUDE.md §5 — fresh schema, no legacy data to match).

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(150) NOT NULL,
    email         VARCHAR(254) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    phone_number  VARCHAR(15),
    first_name    VARCHAR(150),
    last_name     VARCHAR(150),
    is_staff      BOOLEAN NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    is_superuser  BOOLEAN NOT NULL DEFAULT FALSE,
    date_joined   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login    TIMESTAMPTZ,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE auth_refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    jti         UUID NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_auth_refresh_token_token_hash UNIQUE (token_hash),
    CONSTRAINT uk_auth_refresh_token_jti UNIQUE (jti)
);

CREATE INDEX idx_auth_refresh_token_user_revoked ON auth_refresh_token (user_id, revoked_at);
