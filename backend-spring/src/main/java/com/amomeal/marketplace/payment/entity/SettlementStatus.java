package com.amomeal.marketplace.payment.entity;

/**
 * ../../backend/payment/models.py::SettlementRecord.SETTLEMENT_STATUS_CHOICES, plus the
 * {@code "CANCELLED"} value Django writes without declaring it (see {@link SettlementRecord}).
 */
public final class SettlementStatus {

    public static final String PENDING = "PENDING";
    public static final String LEDGER_RECORDED = "LEDGER_RECORDED";
    public static final String PAYOUT_SCHEDULED = "PAYOUT_SCHEDULED";
    public static final String PAYOUT_PROCESSED = "PAYOUT_PROCESSED";
    public static final String PAYOUT_COMPLETED = "PAYOUT_COMPLETED";
    public static final String PAYOUT_FAILED = "PAYOUT_FAILED";
    /** Not a declared choice in Django — written by the COD cancellation branch anyway. */
    public static final String CANCELLED = "CANCELLED";

    private SettlementStatus() {
    }
}
