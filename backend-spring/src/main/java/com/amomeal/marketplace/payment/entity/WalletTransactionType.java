package com.amomeal.marketplace.payment.entity;

/** Mirrors ../../backend/utils/enums.py::WalletTransactionTypeEnum. HOLD is declared but never written by Django. */
public enum WalletTransactionType {
    HOLD,
    RELEASE,
    REFUND,
    PAYOUT
}
