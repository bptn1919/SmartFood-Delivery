-- Mirrors ../../backend/cart/models.py (Cart, CartItem). Fresh schema per
-- CLAUDE.md §5 -- field list/constraints/relationships ported faithfully,
-- physical naming is idiomatic snake_case.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   cart.owner        SET_NULL  -> ON DELETE SET NULL
--   cart_item.cart     CASCADE   -> ON DELETE CASCADE
--   cart_item.dish      CASCADE   -> ON DELETE CASCADE
--
-- References the already-migrated app_user (V1) and dish (V4) tables -- no
-- table is re-declared here. Cart does NOT reserve stock (see
-- CartService's javadoc) -- no reference to dish's stock_reservation table.

CREATE TABLE cart (
    uid        UUID PRIMARY KEY,
    owner_id   BIGINT UNIQUE REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Django: PositiveIntegerField(default=1) for quantity -- no DB-level CHECK
-- constraint in Django either (app-level validation only), so none is added
-- here to stay faithful.
CREATE TABLE cart_item (
    uid           UUID PRIMARY KEY,
    cart_uid      UUID REFERENCES cart (uid) ON DELETE CASCADE,
    dish_uid      UUID REFERENCES dish (uid) ON DELETE CASCADE,
    delivery_date DATE NOT NULL,
    quantity      INTEGER NOT NULL DEFAULT 1,
    is_selected   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_cart_item_cart_dish_date UNIQUE (cart_uid, dish_uid, delivery_date)
);

CREATE INDEX idx_cart_item_cart ON cart_item (cart_uid);
CREATE INDEX idx_cart_item_dish ON cart_item (dish_uid);
