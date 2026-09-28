-- Redis-outage tolerance + transactional outbox, ported from the newer Django reference
-- backend-edit/Personalized-Food-Delivery-Platform commit 9939179.

-- dish migration 0023_dishavailability_updated_at: durable "this (dish, date) row changed"
-- trace for the post-Redis-outage stock counter resync (StockRedisGateway).
ALTER TABLE dish_availability ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_dish_availability_updated_at ON dish_availability (updated_at);
CREATE INDEX idx_stock_reservation_updated_at ON stock_reservation (updated_at);

-- order migration 0016_outboxevent: tasks that must not be lost when the async hand-off
-- fails (Django: RabbitMQ down). Written in the SAME transaction as the business change.
CREATE TABLE outbox_event (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL,
    task_name        VARCHAR(200) NOT NULL,
    payload          JSONB        NOT NULL,
    -- PENDING / SENT (handed to the async executor) / FAILED (gave up after max attempts)
    status           VARCHAR(8)   NOT NULL DEFAULT 'PENDING',
    -- Django PositiveIntegerField
    attempts         INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       TEXT         NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    CONSTRAINT uk_outbox_event_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_outbox_event_status_next_attempt ON outbox_event (status, next_attempt_at);
