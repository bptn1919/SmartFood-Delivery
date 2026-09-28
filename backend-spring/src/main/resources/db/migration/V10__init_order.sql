-- ===========================================================================
-- order module — mirrors ../../backend/order/models.py
-- (Checkout, Order, OrderItem, NotificationIdempotencyKey)
-- ===========================================================================

-- Django: Checkout(BaseModel)
CREATE TABLE checkout (
    uid                  UUID PRIMARY KEY,
    owner_id             BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    full_name            VARCHAR(255) NOT NULL,
    phone_number         VARCHAR(20) NOT NULL,
    sub_total            NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_and_fees         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    delivery_fee         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_price          NUMERIC(30, 2) NOT NULL DEFAULT 0,
    total_discount       NUMERIC(32, 2) NOT NULL DEFAULT 0,
    -- Django: on_delete=SET_NULL ("Không xóa Order nếu Address bị xóa")
    delivery_address_id  BIGINT REFERENCES customer_address (id) ON DELETE SET NULL,
    payment_method       VARCHAR(16) NOT NULL DEFAULT 'COD',
    delivery_date        DATE NOT NULL,
    delivery_time        TIME NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_checkout_owner ON checkout (owner_id);

-- Django: Order(BaseModel). "order" is a reserved word, hence quoted — this exact
-- spelling is what V9__init_voucher.sql's comment block documents.
CREATE TABLE "order" (
    uid                         UUID PRIMARY KEY,
    checkout_uid                UUID NOT NULL REFERENCES checkout (uid) ON DELETE CASCADE,
    owner_id                    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    chef_id                     BIGINT REFERENCES app_user (id) ON DELETE CASCADE,
    delivery_name               VARCHAR(255),
    delivery_phone              VARCHAR(20),
    delivery_address_text       VARCHAR(500),
    delivery_latitude           DOUBLE PRECISION,
    delivery_longitude          DOUBLE PRECISION,
    delivery_type               VARCHAR(20) NOT NULL DEFAULT 'THIRD_PARTY',
    sub_total                   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_and_fees                NUMERIC(12, 2) NOT NULL DEFAULT 0,
    delivery_fee                NUMERIC(12, 2) NOT NULL DEFAULT 0,
    platform_subtotal_discount  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    platform_shipping_discount  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    shop_discount               NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_discount              NUMERIC(15, 2) NOT NULL DEFAULT 0,
    total_price                 NUMERIC(30, 2) NOT NULL DEFAULT 0,
    status                      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    payment_status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_checkout ON "order" (checkout_uid);
CREATE INDEX idx_order_owner_status ON "order" (owner_id, status);
CREATE INDEX idx_order_chef_status ON "order" (chef_id, status);

-- Django: OrderItem(models.Model) — auto integer PK, the id StockReservation points at.
CREATE TABLE order_item (
    id              BIGSERIAL PRIMARY KEY,
    order_uid       UUID NOT NULL REFERENCES "order" (uid) ON DELETE CASCADE,
    -- Django: on_delete=SET_NULL (deleting a dish must not erase order history)
    dish_uid        UUID REFERENCES dish (uid) ON DELETE SET NULL,
    dish_name       VARCHAR(255) NOT NULL,
    dish_image_url  VARCHAR(500),
    quantity        INTEGER NOT NULL DEFAULT 1,
    price           NUMERIC(12, 2) NOT NULL,
    -- Django PositiveIntegerField
    CONSTRAINT ck_order_item_quantity_non_negative CHECK (quantity >= 0)
);

CREATE INDEX idx_order_item_order ON order_item (order_uid);
CREATE INDEX idx_order_item_dish ON order_item (dish_uid);

-- Django: NotificationIdempotencyKey — the UNIQUE(key) is the whole mechanism.
CREATE TABLE notification_idempotency_key (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(255) NOT NULL,
    task_name   VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_idempotency_key UNIQUE (key)
);

CREATE INDEX idx_notification_idempotency_task_name ON notification_idempotency_key (task_name);

-- ---------------------------------------------------------------------------
-- The FK V4__init_dish.sql reserved for this module (Django:
-- StockReservation.order_item = OneToOneField("order.OrderItem", CASCADE)).
-- The UNIQUE(order_item_id) from V4 already gives the one-to-one half.
-- ---------------------------------------------------------------------------
ALTER TABLE stock_reservation
    ADD CONSTRAINT fk_stock_reservation_order_item
    FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE CASCADE;

-- PORT-NOTE: stock_reservation.order_uid stays a plain denormalized column (V4's
-- comment: Django reaches it through order_item.order_id; the sweep reads it
-- standalone). It is always consistent because only OrderService writes it.
--
-- PORT-NOTE — deliberately NOT added (flagged in PROGRESS.md): the two
-- applied_voucher FKs V9's comment suggests (checkout_uid -> checkout,
-- order_uid -> "order", both CASCADE). voucher's own pre-existing Postgres-backed
-- tests (VoucherReservationServiceTest) reserve against synthetic order uids with
-- no order row, so the FK would break them, and voucher is out of this task's
-- scope. The one behavior that FK's CASCADE carries in Django — deleting the
-- stale DRAFT orders in checkout() also deletes their AppliedVoucher rows — is
-- emulated explicitly in OrderService.checkout.
