package com.amomeal.marketplace.payment.entity;

/** Mirrors ../../backend/payment/models.py::PayoutLedger.LEDGER_TYPE_CHOICES. */
public enum LedgerType {
    CHEF_PAYOUT,
    PLATFORM_REVENUE,
    PAYOUT_REVERSAL
}
