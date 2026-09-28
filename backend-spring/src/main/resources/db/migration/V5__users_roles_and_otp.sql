-- users module, second pass: the REAL role model + OTP flows.
--
-- app_user_role mirrors Django's auth_user_groups. Django stores roles as rows
-- in auth_group ("CUSTOMER"/"CHEF"/"ADMIN", created by
-- users/management/commands/setup_permissions.py) joined through
-- auth_user_groups. Since that group set is fixed and never edited at runtime,
-- this port keeps the join table but inlines the group identity as an enum
-- value instead of an FK to a 3-row lookup table (CLAUDE.md §5: fresh schema,
-- idiomatic naming, faithful semantics).
--
-- The matching permission matrix (Django auth_permission /
-- auth_group_permissions, populated by the same management command from the
-- hardcoded ID lists that ../../auth_permission.json documents) is likewise a
-- compile-time constant here — see users/service/RolePermissions.java.

CREATE TABLE app_user_role (
    user_id BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role    VARCHAR(16) NOT NULL,
    CONSTRAINT pk_app_user_role PRIMARY KEY (user_id, role)
);

CREATE INDEX idx_app_user_role_role ON app_user_role (role);

-- Backfill: every pre-existing account predates the role system and was created
-- by this port's /api/auth/register, which mirrors Django's create_user — and
-- Django's create_user unconditionally adds the CUSTOMER group.
INSERT INTO app_user_role (user_id, role) SELECT id, 'CUSTOMER' FROM app_user;
-- is_staff was this port's (wrong) ADMIN stand-in before the real model was
-- investigated; anyone carrying it keeps an equivalent capability.
INSERT INTO app_user_role (user_id, role) SELECT id, 'ADMIN' FROM app_user WHERE is_staff;

-- Mirrors ../../backend/users/models.py::UserOTP.
CREATE TABLE user_otp (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    otp_hash             TEXT         NOT NULL,
    attempts             INTEGER      NOT NULL DEFAULT 0,
    otp_verified         BOOLEAN      NOT NULL DEFAULT FALSE,
    reset_session_token  VARCHAR(255) NOT NULL,
    purpose              VARCHAR(16)  NOT NULL,
    target_email         VARCHAR(254),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_otp_reset_session_token UNIQUE (reset_session_token)
);

CREATE INDEX idx_user_otp_user_active ON user_otp (user_id, active);
