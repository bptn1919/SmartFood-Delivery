-- Mirrors ../../backend/voucher/models.py (Voucher, AppliedVoucher). Fresh schema per
-- CLAUDE.md §5 -- field list/constraints/relationships ported faithfully, physical naming is
-- idiomatic snake_case.
--
-- FK cascade behavior mirrors Django's on_delete=:
--   voucher.chef                CASCADE  -> ON DELETE CASCADE (nullable FK; Django declares it
--                                           CASCADE even though null=True/blank=True)
--   applied_voucher.voucher     CASCADE  -> ON DELETE CASCADE
--   applied_voucher.user        CASCADE  -> ON DELETE CASCADE
--
-- References the already-migrated app_user (V1) table -- no table is re-declared here.

CREATE TABLE voucher (
    uid                  UUID PRIMARY KEY,
    chef_id              BIGINT REFERENCES app_user (id) ON DELETE CASCADE,
    code                 VARCHAR(50) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    description          TEXT,
    voucher_type         VARCHAR(30) NOT NULL,
    discount_type        VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    discount_value       NUMERIC(12, 2) NOT NULL,
    max_discount_amount  NUMERIC(12, 2),
    min_order_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    start_date           TIMESTAMPTZ NOT NULL,
    end_date             TIMESTAMPTZ NOT NULL,
    usage_limit          INTEGER,
    usage_limit_per_user INTEGER NOT NULL DEFAULT 1,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Django: unique_together = [["chef", "code"]]. NOTE: the app layer
    -- (VoucherService.createVoucher, matching Django's VoucherORM.check_code_exists) enforces
    -- code uniqueness GLOBALLY, across all chefs -- stricter than this DB constraint. Ported
    -- faithfully rather than "fixed" into a matching global unique index, since the DB
    -- constraint really is only per-chef in Django too.
    CONSTRAINT uk_voucher_chef_code UNIQUE (chef_id, code)
);

CREATE INDEX idx_voucher_chef_code ON voucher (chef_id, code);
CREATE INDEX idx_voucher_chef_active_window ON voucher (chef_id, is_active, start_date, end_date);
CREATE INDEX idx_voucher_code ON voucher (code);

-- Django: AppliedVoucher is a plain `models.Model` (NOT BaseModel) -- Django's default
-- auto-incrementing integer PK, not a UUID uid, hence BIGSERIAL here.
CREATE TABLE applied_voucher (
    id                     BIGSERIAL PRIMARY KEY,
    voucher_uid            UUID NOT NULL REFERENCES voucher (uid) ON DELETE CASCADE,
    -- PORT-NOTE: order/checkout FK -- `order` isn't ported yet (same precedent as
    -- dish.StockReservation.order_item_id/order_uid, see V4__init_dish.sql's comment block).
    -- Plain UUID columns for now. `order`'s future port should add:
    --   ALTER TABLE applied_voucher ADD CONSTRAINT fk_applied_voucher_checkout
    --       FOREIGN KEY (checkout_uid) REFERENCES checkout (uid) ON DELETE CASCADE;
    --   ALTER TABLE applied_voucher ADD CONSTRAINT fk_applied_voucher_order
    --       FOREIGN KEY (order_uid) REFERENCES "order" (uid) ON DELETE CASCADE;
    checkout_uid           UUID,
    order_uid              UUID,
    user_id                BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    voucher_type           VARCHAR(30) NOT NULL,
    discount_amount        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status                 VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    reservation_expires_at TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_applied_voucher_voucher ON applied_voucher (voucher_uid);
CREATE INDEX idx_applied_voucher_user ON applied_voucher (user_id);
CREATE INDEX idx_applied_voucher_checkout ON applied_voucher (checkout_uid);
CREATE INDEX idx_applied_voucher_order ON applied_voucher (order_uid);

-- Django Meta.constraints: at most 1 SHOP_VOUCHER reservation row per order / 1
-- PLATFORM_SUBTOTAL row per checkout / 1 PLATFORM_SHIPPING row per checkout, ported as
-- Postgres partial unique indexes (Django's UniqueConstraint(..., condition=Q(...))).
--
-- PORT-NOTE -- a real, reachable Django bug preserved verbatim (see AppliedVoucher's class
-- javadoc and PROGRESS.md): these constraints apply to ALL rows of that voucher_type for that
-- order/checkout regardless of `status`. Once a SHOP_VOUCHER reservation for an order expires
-- (status -> EXPIRED, row NOT deleted), a second reservation attempt for that same order can
-- never INSERT a new row -- the unique index still sees the stale row occupying that
-- (order_uid, voucher_type) slot, and the INSERT fails with an uncaught constraint violation
-- (500 CONTACT_ADMIN_FOR_SUPPORT via GlobalExceptionHandler's catch-all, matching Django's own
-- uncaught IntegrityError). Not fixed -- CLAUDE.md §0.1.
CREATE UNIQUE INDEX uk_applied_voucher_shop_per_order
    ON applied_voucher (order_uid, voucher_type)
    WHERE voucher_type = 'SHOP_VOUCHER';

CREATE UNIQUE INDEX uk_applied_voucher_platform_subtotal_per_checkout
    ON applied_voucher (checkout_uid, voucher_type)
    WHERE voucher_type = 'PLATFORM_SUBTOTAL';

CREATE UNIQUE INDEX uk_applied_voucher_platform_shipping_per_checkout
    ON applied_voucher (checkout_uid, voucher_type)
    WHERE voucher_type = 'PLATFORM_SHIPPING';
