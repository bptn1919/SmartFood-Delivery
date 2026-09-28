package com.amomeal.marketplace.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Wallet/escrow settings of the payment module (see application.yml {@code app.payment.*}). */
@ConfigurationProperties(prefix = "app.payment")
@Getter
@Setter
public class PaymentProperties {

    /**
     * Django {@code settings.WALLET_CHAIN_SECRET = os.getenv("WALLET_CHAIN_SECRET", "")} — keys
     * the wallet balance signature AND the wallet ledger hash chain. Defaults to the EMPTY
     * string exactly like Django (whose model-level {@code getattr(..., SECRET_KEY)} fallback
     * never fires because the setting always exists). Flagged: set it in production.
     */
    private String walletChainSecret = "";

    /**
     * Django {@code getattr(settings, "PAYMENT_EVENT_SECRET", settings.SECRET_KEY)} — the setting
     * is not defined in Django's settings.py, so Django always uses SECRET_KEY. This port has no
     * Django SECRET_KEY; application.yml binds PAYMENT_EVENT_SECRET, then SECRET_KEY, then the
     * JWT secret.
     */
    private String eventSecret = "";

    /** Django {@code _create_payout_with_retry}: {@code backoff_seconds = 0.6}, doubled per attempt. Tests shrink it. */
    private long payoutRetryBackoffMs = 600;

    /**
     * {@code true} = Django's ACTUAL behavior in {@code _sync_successful_payment}:
     * {@code stock_reservation.confirm()} returns a bool, but the caller tests
     * {@code outcome in ("confirmed", "already_confirmed")} (strings), which is never true —
     * so every successful PayOS confirmation falls into the late-webhook "recovery" branch,
     * {@code reserve()} refuses to reopen the just-CONFIRMED hold, and the order is CANCELLED
     * and refunded to the customer's internal wallet. Kept only to reproduce that bug on demand.
     * {@code false} = DEFAULT since 2026-09-23 (the user chose "fix", PROGRESS.md payment open
     * question #1): the behavior the code's own comments describe (confirmed / already-confirmed
     * pass through; only a genuinely expired hold goes through re-reserve, then refund if sold
     * out).
     */
    private boolean preserveDjangoConfirmOutcomeBug = false;

    /** Scheduled PayOS reconciliation job (Spring-only addition, not in Django); see {@link Reconciliation}. */
    private Reconciliation reconciliation = new Reconciliation();

    /**
     * {@code app.payment.reconciliation.*}: a scheduled pull of PayOS for PENDING payments whose
     * webhook may have been lost (Django only reconciles on demand, when someone opens the
     * payment status). It reuses {@code PaymentService#syncPaymentByOrderCode} unchanged.
     */
    @Getter
    @Setter
    public static class Reconciliation {
        /** Whether the {@code @Scheduled} job runs (tests switch it off). */
        private boolean enabled = true;
        /** fixedDelay (and initial delay) between ticks. Read by the scheduler annotation directly. */
        private java.time.Duration interval = java.time.Duration.ofSeconds(60);
        /** Only payments PENDING for at least this long, so the job does not race the normal webhook. */
        private java.time.Duration minAge = java.time.Duration.ofMinutes(2);
        /** Payments older than this are no longer polled (left to the existing expiry handling). */
        private java.time.Duration maxAge = java.time.Duration.ofHours(24);
        /** Max payments examined (i.e. PayOS calls made, sequentially) per tick. */
        private int batchSize = 50;
    }
}
