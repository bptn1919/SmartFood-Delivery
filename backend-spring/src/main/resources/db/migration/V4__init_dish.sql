-- Mirrors ../../backend/dish/models.py (DishLocation, Dish, DishIngredient,
-- DishAvailability, DishAlias, StockReservation). Fresh schema per CLAUDE.md §5 —
-- field list / constraints / relationships ported faithfully, physical naming is
-- idiomatic snake_case via Hibernate's default strategy.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   dish_location.parent                         PROTECT   -> ON DELETE RESTRICT
--   dish.location / owner / updater / attachment SET_NULL  -> ON DELETE SET NULL
--   dish_ingredient.dish                         CASCADE   -> ON DELETE CASCADE
--   dish_ingredient.ingredient                   RESTRICT  -> ON DELETE RESTRICT
--   dish_ingredient.suggestion / created_by / updated_by  SET_NULL -> ON DELETE SET NULL
--   dish_availability.dish / dish_alias.dish / stock_reservation.dish  CASCADE
--
-- References the already-migrated app_user (V1), attachment (V2) and
-- ingredient/ingredient_suggestion (V3) tables — no table is re-declared here.

-- ---------------------------------------------------------------------------
-- DishLocation: REGION -> SUBREGION -> COUNTRY geography tree
-- ---------------------------------------------------------------------------
CREATE TABLE dish_location (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(255) NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    parent_id  BIGINT REFERENCES dish_location (id) ON DELETE RESTRICT,
    CONSTRAINT uk_dish_location_slug UNIQUE (slug),
    CONSTRAINT uk_dish_location_name_parent UNIQUE (name, parent_id),
    -- Django CheckConstraint "valid_location_parent_null": a REGION must be a root,
    -- SUBREGION/COUNTRY must have a parent.
    CONSTRAINT valid_location_parent_null CHECK (
        (type = 'REGION'    AND parent_id IS NULL)
     OR (type = 'SUBREGION' AND parent_id IS NOT NULL)
     OR (type = 'COUNTRY'   AND parent_id IS NOT NULL)
    )
);

CREATE INDEX idx_dish_location_type ON dish_location (type);
CREATE INDEX idx_dish_location_parent ON dish_location (parent_id);

-- ---------------------------------------------------------------------------
-- Dish
-- ---------------------------------------------------------------------------
CREATE TABLE dish (
    uid             UUID PRIMARY KEY,
    name            TEXT NOT NULL,
    name_no_accent  TEXT NOT NULL,
    category        VARCHAR(16) NOT NULL,
    description     TEXT,
    price           NUMERIC(12, 2) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    location_id     BIGINT REFERENCES dish_location (id) ON DELETE SET NULL,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id        BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updater_id      BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    attachment_uid  UUID REFERENCES attachment (uid) ON DELETE SET NULL,
    avg_rating      DOUBLE PRECISION NOT NULL DEFAULT 0,
    final_score     DOUBLE PRECISION NOT NULL DEFAULT 0,
    is_suspended    BOOLEAN NOT NULL DEFAULT FALSE,
    -- Django PositiveSmallIntegerField(default=1) — people per portion, used to
    -- normalize nutrition per serving (Layer 3 validation).
    serving_size    INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dish_deleted ON dish (deleted);
CREATE INDEX idx_dish_owner ON dish (owner_id);
CREATE INDEX idx_dish_category ON dish (category);
CREATE INDEX idx_dish_location ON dish (location_id);
-- Search hits name_no_accent with both an exact (iexact) and a LIKE '%...%' filter.
CREATE INDEX idx_dish_name_no_accent ON dish (name_no_accent);

-- ---------------------------------------------------------------------------
-- DishIngredient: one ingredient line of a dish + its nutrition snapshot
-- ---------------------------------------------------------------------------
CREATE TABLE dish_ingredient (
    uid              UUID PRIMARY KEY,
    dish_uid         UUID NOT NULL REFERENCES dish (uid) ON DELETE CASCADE,
    ingredient_uid   UUID REFERENCES ingredient (uid) ON DELETE RESTRICT,
    custom_name      TEXT,
    source           VARCHAR(20) NOT NULL DEFAULT 'USDA',
    approval_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    suggestion_uid   UUID REFERENCES ingredient_suggestion (uid) ON DELETE SET NULL,
    created_by_id    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by_id    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
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
    confidence       DOUBLE PRECISION DEFAULT 1.0,
    deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Django unique_together = ("dish", "ingredient"). Postgres treats NULLs as
    -- distinct, so (like Django) this only constrains rows that reference a real
    -- ingredient — a dish can hold many custom-name rows.
    CONSTRAINT uk_dish_ingredient_dish_ingredient UNIQUE (dish_uid, ingredient_uid)
);

CREATE INDEX idx_dish_ingredient_dish ON dish_ingredient (dish_uid);
CREATE INDEX idx_dish_ingredient_ingredient ON dish_ingredient (ingredient_uid);
CREATE INDEX idx_dish_ingredient_suggestion ON dish_ingredient (suggestion_uid);

-- ---------------------------------------------------------------------------
-- DishAvailability: per-day inventory (source of truth for stock)
-- ---------------------------------------------------------------------------
CREATE TABLE dish_availability (
    id                  BIGSERIAL PRIMARY KEY,
    dish_uid            UUID NOT NULL REFERENCES dish (uid) ON DELETE CASCADE,
    available_date      DATE NOT NULL,
    is_available        BOOLEAN NOT NULL DEFAULT TRUE,
    available_quantity  INTEGER NOT NULL DEFAULT 0,
    note                TEXT,
    CONSTRAINT uk_dish_availability_dish_date UNIQUE (dish_uid, available_date),
    -- Django PositiveIntegerField
    CONSTRAINT ck_dish_availability_quantity_non_negative CHECK (available_quantity >= 0)
);

CREATE INDEX idx_dish_availability_date ON dish_availability (available_date);

-- ---------------------------------------------------------------------------
-- DishAlias: alternative dish names for fuzzy/semantic search
-- ---------------------------------------------------------------------------
CREATE TABLE dish_alias (
    uid                   UUID PRIMARY KEY,
    dish_uid              UUID NOT NULL REFERENCES dish (uid) ON DELETE CASCADE,
    alias_name            TEXT NOT NULL,
    alias_name_no_accent  TEXT NOT NULL,
    similarity_score      DOUBLE PRECISION NOT NULL DEFAULT 0.9,
    alias_type            VARCHAR(20) NOT NULL DEFAULT 'SYNONYM',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dish_alias_dish_name_no_accent UNIQUE (dish_uid, alias_name_no_accent)
);

CREATE INDEX idx_dish_alias_dish ON dish_alias (dish_uid);
CREATE INDEX idx_dish_alias_name_no_accent ON dish_alias (alias_name_no_accent);
CREATE INDEX idx_dish_alias_type ON dish_alias (alias_type);

-- ---------------------------------------------------------------------------
-- StockReservation: durable ledger for checkout stock holds
-- ---------------------------------------------------------------------------
-- PORT-NOTE: Django declares order_item as a OneToOneField to order.OrderItem
-- (on_delete=CASCADE). The `order` app is not ported yet (CLAUDE.md §7 orders it
-- after dish), so there is no order_item table to reference. The semantics the
-- reservation logic actually depends on — AT MOST ONE ledger row per order item —
-- are enforced here by the UNIQUE constraint on order_item_id; the `order` port
-- adds the FK itself in its own migration:
--     ALTER TABLE stock_reservation
--       ADD CONSTRAINT fk_stock_reservation_order_item
--       FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE CASCADE;
-- order_uid is likewise denormalized (Django reaches it via
-- reservation.order_item.order_id) so the expiry sweep works standalone today.
CREATE TABLE stock_reservation (
    id              BIGSERIAL PRIMARY KEY,
    order_item_id   BIGINT NOT NULL,
    order_uid       UUID,
    dish_uid        UUID NOT NULL REFERENCES dish (uid) ON DELETE CASCADE,
    available_date  DATE NOT NULL,
    quantity        INTEGER NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_stock_reservation_order_item UNIQUE (order_item_id),
    -- Django PositiveIntegerField
    CONSTRAINT ck_stock_reservation_quantity_non_negative CHECK (quantity >= 0)
);

-- Django Meta.indexes, 1:1
CREATE INDEX idx_stock_reservation_status_expires ON stock_reservation (status, expires_at);
CREATE INDEX idx_stock_reservation_dish_date_status ON stock_reservation (dish_uid, available_date, status);
