-- Mirrors ../../backend/menu/models.py (Menu, MenuDish). Fresh schema per
-- CLAUDE.md §5 — field list/constraints/relationships ported faithfully,
-- physical naming is idiomatic snake_case via Hibernate's default strategy.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   menu.chef        CASCADE   -> ON DELETE CASCADE
--   menu.updater     SET_NULL  -> ON DELETE SET NULL
--   menu_dish.menu   CASCADE   -> ON DELETE CASCADE
--   menu_dish.dish   CASCADE   -> ON DELETE CASCADE
--
-- References the already-migrated app_user (V1) and dish (V4) tables — no
-- table is re-declared here.

-- ---------------------------------------------------------------------------
-- Menu: chef-curated dish grouping (BaseModel: uid/created_at/updated_at)
-- ---------------------------------------------------------------------------
CREATE TABLE menu (
    uid         UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    chef_id     BIGINT REFERENCES app_user (id) ON DELETE CASCADE,
    updater_id  BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_menu_chef ON menu (chef_id);
CREATE INDEX idx_menu_deleted ON menu (deleted);
CREATE INDEX idx_menu_status ON menu (status);

-- ---------------------------------------------------------------------------
-- MenuDish: link table (Django: plain models.Model, NOT a BaseModel -> a
-- default auto-increment integer PK, not a uid, unlike every other table
-- ported so far).
-- ---------------------------------------------------------------------------
CREATE TABLE menu_dish (
    id        BIGSERIAL PRIMARY KEY,
    menu_id   UUID NOT NULL REFERENCES menu (uid) ON DELETE CASCADE,
    dish_id   UUID REFERENCES dish (uid) ON DELETE CASCADE,
    position  INTEGER NOT NULL DEFAULT 0,
    active    BOOLEAN NOT NULL DEFAULT TRUE,
    -- Django unique_together = ("menu", "dish").
    CONSTRAINT uk_menu_dish_menu_dish UNIQUE (menu_id, dish_id)
);

CREATE INDEX idx_menu_dish_menu ON menu_dish (menu_id);
CREATE INDEX idx_menu_dish_dish ON menu_dish (dish_id);
