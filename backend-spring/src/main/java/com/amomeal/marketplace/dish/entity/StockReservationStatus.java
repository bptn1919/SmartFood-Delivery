package com.amomeal.marketplace.dish.entity;

/**
 * Mirrors ../../backend/utils/enums.py::StockReservationStatus — the durable
 * hold ledger's state machine (see
 * {@link com.amomeal.marketplace.dish.service.StockReservationService}).
 *
 * <pre>
 *   RESERVED  -> CONFIRMED  (payment succeeded; Postgres inventory deducted)
 *   RESERVED  -> RELEASED   (explicit cancel/rollback, before confirm)
 *   RESERVED  -> EXPIRED    (TTL sweep released it, before confirm)
 *   CONFIRMED -> CANCELLED  (cancellation AFTER a final sale; inventory restored)
 * </pre>
 */
public enum StockReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED,
    EXPIRED,
    CANCELLED
}
