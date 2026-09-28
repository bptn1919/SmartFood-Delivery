-- Mirrors ../../backend/ingredient/orm/ingredient.py (Ingredient, IngredientAlias,
-- IngredientSuggestion, FavouriteIngredient, AllergicIngredient). Fresh schema per
-- CLAUDE.md §5 — field list/constraints/relationships ported faithfully, physical
-- naming is idiomatic (snake_case via Hibernate's default strategy).
--
-- FK cascade behavior mirrors Django's on_delete=:
--   owner/updater/created_by/verified_by (User FKs)      -> SET_NULL
--   ingredient.attachment / suggestion.attachment        -> SET_NULL
--   alias.ingredient, favourite.ingredient, allergic.ingredient (CASCADE in Django) -> CASCADE
--   suggestion.ingredient, suggestion.resolved_alias     -> SET_NULL

CREATE TABLE ingredient (
    uid              UUID PRIMARY KEY,
    name             TEXT NOT NULL,
    name_no_accent   TEXT NOT NULL,
    category         VARCHAR(16) NOT NULL,
    weight           DOUBLE PRECISION,
    energy           DOUBLE PRECISION,
    protein          DOUBLE PRECISION,
    lipid            DOUBLE PRECISION,
    carbohydrate     DOUBLE PRECISION,
    fiber            DOUBLE PRECISION,
    natri            DOUBLE PRECISION,
    kali             DOUBLE PRECISION,
    cholesterol      DOUBLE PRECISION,
    retinol          DOUBLE PRECISION,
    caroten          DOUBLE PRECISION,
    vitamin_b_1      DOUBLE PRECISION,
    vitamin_b_2      DOUBLE PRECISION,
    vitamin_pp       DOUBLE PRECISION,
    vitamin_c        DOUBLE PRECISION,
    calcium          DOUBLE PRECISION,
    phosphorus       DOUBLE PRECISION,
    fe               DOUBLE PRECISION,
    mg               DOUBLE PRECISION,
    zn               DOUBLE PRECISION,
    source           VARCHAR(15) NOT NULL DEFAULT 'USDA',
    deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id         BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updater_id       BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    attachment_uid   UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ingredient_name_no_accent ON ingredient (name_no_accent);
CREATE INDEX idx_ingredient_deleted ON ingredient (deleted);
CREATE INDEX idx_ingredient_category ON ingredient (category);

CREATE TABLE ingredient_alias (
    uid              UUID PRIMARY KEY,
    ingredient_uid   UUID NOT NULL REFERENCES ingredient (uid) ON DELETE CASCADE,
    alias            TEXT NOT NULL,
    alias_no_accent  TEXT NOT NULL,
    created_by_id    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ingredient_alias_no_accent UNIQUE (alias_no_accent)
);

CREATE INDEX idx_ingredient_alias_ingredient_uid ON ingredient_alias (ingredient_uid);

CREATE TABLE ingredient_suggestion (
    uid                       UUID PRIMARY KEY,
    suggested_name            TEXT NOT NULL,
    suggested_name_no_accent  TEXT NOT NULL,
    suggested_category        VARCHAR(16),
    created_by_id             BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    status                    VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    verified_by_id            BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    verified_at               TIMESTAMPTZ,
    rejection_reason          TEXT,
    ingredient_uid            UUID REFERENCES ingredient (uid) ON DELETE SET NULL,
    resolved_alias_uid        UUID REFERENCES ingredient_alias (uid) ON DELETE SET NULL,
    resolution_note           TEXT,
    attachment_uid            UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    deleted                   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ingredient_suggestion_created_by ON ingredient_suggestion (created_by_id);
CREATE INDEX idx_ingredient_suggestion_status ON ingredient_suggestion (status);
CREATE INDEX idx_ingredient_suggestion_deleted ON ingredient_suggestion (deleted);

CREATE TABLE favourite_ingredient (
    uid              UUID PRIMARY KEY,
    ingredient_uid   UUID NOT NULL REFERENCES ingredient (uid) ON DELETE CASCADE,
    user_id          BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_favourite_ingredient_user_ingredient UNIQUE (ingredient_uid, user_id)
);

CREATE TABLE allergic_ingredient (
    uid              UUID PRIMARY KEY,
    ingredient_uid   UUID NOT NULL REFERENCES ingredient (uid) ON DELETE CASCADE,
    user_id          BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_allergic_ingredient_user_ingredient UNIQUE (ingredient_uid, user_id)
);
