-- Mirrors ../../backend/profile/models.py (CustomerProfile, CustomerFavoriteDish,
-- ChefProfile, ChefPaymentInfo, CustomerAddress). Fresh schema per CLAUDE.md §5 —
-- field list/constraints/relationships ported faithfully, physical naming is
-- idiomatic snake_case via Hibernate's default strategy.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   customer_profile.user           CASCADE   -> ON DELETE CASCADE
--   customer_profile.avatar         SET_NULL  -> ON DELETE SET NULL
--   customer_favorite_dish.user     CASCADE   -> ON DELETE CASCADE
--   customer_favorite_dish.dish     CASCADE   -> ON DELETE CASCADE
--   chef_profile.user               CASCADE   -> ON DELETE CASCADE
--   chef_profile.avatar             SET_NULL  -> ON DELETE SET NULL
--   chef_payment_info.user          CASCADE   -> ON DELETE CASCADE
--   customer_address.user           CASCADE   -> ON DELETE CASCADE
--
-- References the already-migrated app_user (V1), attachment (V2) and dish (V4)
-- tables — no table is re-declared here.

-- ---------------------------------------------------------------------------
-- CustomerProfile — diet_mode/diet_level/allergy_mode are inputs the
-- not-yet-ported `recommendation` module will consume later (CLAUDE.md §7);
-- modeled faithfully here, no recommendation logic in this port.
-- ---------------------------------------------------------------------------
CREATE TABLE customer_profile (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    bio            TEXT,
    attachment_uid UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    points         INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    is_onboarded   BOOLEAN NOT NULL DEFAULT FALSE,
    diet_mode      VARCHAR(17) NOT NULL DEFAULT 'NONE',
    diet_level     VARCHAR(16) NOT NULL DEFAULT 'NONE',
    allergy_mode   VARCHAR(16) NOT NULL DEFAULT 'WARN',
    -- Django: Meta.constraints CheckConstraint("diet_mode_level_consistency") —
    -- either both NONE, or both non-NONE. See CustomerProfile's javadoc for the
    -- real Django quirk this enables (an update that sets diet_mode=NONE alone,
    -- leaving a stale non-NONE diet_level in place, crashes with a constraint
    -- violation -> uncaught 500, preserved on purpose).
    CONSTRAINT chk_diet_mode_level_consistency CHECK (
        (diet_mode = 'NONE' AND diet_level = 'NONE') OR (diet_mode <> 'NONE' AND diet_level <> 'NONE')
    )
);

-- ---------------------------------------------------------------------------
-- CustomerFavoriteDish (BaseModel: uid/created_at/updated_at, soft-deleted,
-- unique_together user+dish). Reuses `dish`'s already-migrated `dish` table.
-- ---------------------------------------------------------------------------
CREATE TABLE customer_favorite_dish (
    uid        UUID PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    dish_uid   UUID NOT NULL REFERENCES dish (uid) ON DELETE CASCADE,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_customer_favorite_dish_user_dish UNIQUE (user_id, dish_uid)
);

CREATE INDEX idx_customer_favorite_dish_user ON customer_favorite_dish (user_id);

-- ---------------------------------------------------------------------------
-- ChefProfile — created by ProfileService.createChefProfile, called both from
-- POST /api/chef-profiles/ and the CUSTOMER->CHEF upgrade flow.
-- ---------------------------------------------------------------------------
CREATE TABLE chef_profile (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    bio                  TEXT,
    specialty            TEXT,
    attachment_uid       UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    kitchen_address      VARCHAR(255),
    kitchen_street       VARCHAR(255),
    kitchen_ward         VARCHAR(100),
    kitchen_district     VARCHAR(100),
    kitchen_city         VARCHAR(100),
    kitchen_latitude     DOUBLE PRECISION,
    kitchen_longitude    DOUBLE PRECISION,
    rating               DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    number_of_orders     INTEGER NOT NULL DEFAULT 0,
    is_accepting_orders  BOOLEAN NOT NULL DEFAULT TRUE,
    suspension_level     VARCHAR(16) NOT NULL DEFAULT 'NONE'
);

CREATE INDEX idx_chef_profile_rating ON chef_profile (rating);

-- ---------------------------------------------------------------------------
-- ChefPaymentInfo — bank details for payouts. bank_name stores the raw
-- VietnamBankEnum value string (validated in ChefPaymentService, not a DB enum).
-- ---------------------------------------------------------------------------
CREATE TABLE chef_payment_info (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    bank_name             VARCHAR(255) NOT NULL,
    bank_code             VARCHAR(20) NOT NULL,
    bank_account_number   VARCHAR(50) NOT NULL,
    bank_account_name     VARCHAR(255) NOT NULL,
    bank_branch           VARCHAR(255),
    citizen_id            VARCHAR(20),
    tax_code              VARCHAR(20),
    is_verified           BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted               BOOLEAN NOT NULL DEFAULT FALSE
);

-- ---------------------------------------------------------------------------
-- CustomerAddress — plain Django model (no BaseModel: no uid, no timestamps).
-- PORT-NOTE: no repository query filters `deleted` (see the entity's javadoc)
-- — the column exists but nothing in this port hides on it either, matching
-- Django's own CustomerORM read paths exactly.
-- ---------------------------------------------------------------------------
CREATE TABLE customer_address (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    address  VARCHAR(255) NOT NULL,
    street   VARCHAR(255) NOT NULL,
    ward     VARCHAR(100) NOT NULL,
    district VARCHAR(100) NOT NULL,
    city     VARCHAR(100) NOT NULL,
    latitude  DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    deleted  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_customer_address_user ON customer_address (user_id);
