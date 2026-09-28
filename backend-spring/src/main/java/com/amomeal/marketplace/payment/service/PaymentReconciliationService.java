package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.config.PaymentProperties;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scheduled PayOS reconciliation (added here 2026-09-25 as a Spring-only job; the newer Django
 * reference then added the equivalent {@code payment/tasks.py::reconcile_pending_payos_payments}
 * in backend-edit commit 9939179 — same filter (PAYOS, order code set, PENDING, created between
 * now-24h and now-120s), same 60 s / batch 50 defaults, same {@code sync_payment_by_order_code}
 * path, same per-payment error isolation; the only difference is this port's round-robin id
 * cursor vs Django's {@code order_by("created_at")[:50]}, a strict superset). Covers a LOST
 * webhook: without it a paid PENDING payment stays PENDING until its stock hold expires and the
 * order is cancelled.
 *
 * <p>Per candidate it calls the existing {@link PaymentService#syncPaymentByOrderCode} — the same
 * code path as the webhook/return URL (PAID -> HOLDING + stock confirm + late-webhook recovery,
 * CANCELLED/EXPIRED -> CANCELLED/FAILED) — so no transition logic is duplicated. This class is
 * deliberately NOT transactional (CLAUDE.md 8b): each payment is handled by the service, which
 * manages its own transaction boundaries, and a slow/failing PayOS call must not hold a
 * connection or abort the batch.
 *
 * <p>Exactly-once vs a concurrent webhook: the sync is idempotent for an already
 * HOLDING/SUCCESS/RELEASED/REFUND_* payment, the PENDING -> HOLDING transition is validated
 * against the freshly read state, stock {@code confirm()} is a no-op the second time and
 * notifications carry idempotency keys. See {@code PaymentReconciliationTest}.
 *
 * <p>Sequential, at most {@code batch-size} PayOS calls per tick, and a round-robin id cursor so
 * payments that are genuinely still unpaid at PayOS do not monopolize every batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationService {

    /** What one tick did. */
    public record Result(int examined, int failed) {
    }

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentService paymentService;
    private final PaymentProperties properties;
    private final PaymentTx tx;

    /** Round-robin position (payment id) between ticks; single scheduler thread, so plain volatile. */
    private volatile long cursor = 0;

    public Result reconcileOnce() {
        PaymentProperties.Reconciliation cfg = properties.getReconciliation();
        Instant now = Instant.now();
        Instant createdBefore = now.minus(cfg.getMinAge());
        Instant createdAfter = now.minus(cfg.getMaxAge());
        int batchSize = Math.max(1, cfg.getBatchSize());

        List<PaymentTransaction> batch = candidates(createdAfter, createdBefore, cursor, batchSize);
        if (batch.isEmpty() && cursor > 0) {
            cursor = 0; // wrapped around
            batch = candidates(createdAfter, createdBefore, 0, batchSize);
        }
        int failed = 0;
        for (PaymentTransaction payment : batch) {
            cursor = payment.getId();
            try {
                Map<String, Object> result = paymentService.syncPaymentByOrderCode(payment.getPayosOrderCode());
                if (Boolean.FALSE.equals(result.get("success"))) {
                    failed++;
                    log.warn("[PayOS Reconcile] payment {} (order code {}) not synced: {}", payment.getUid(),
                            payment.getPayosOrderCode(), result.get("error"));
                } else {
                    log.info("[PayOS Reconcile] payment {} (order code {}) -> {}", payment.getUid(),
                            payment.getPayosOrderCode(), result.get("status"));
                }
            } catch (RuntimeException ex) {
                failed++;
                log.error("[PayOS Reconcile] payment {} (order code {}) failed: {}", payment.getUid(),
                        payment.getPayosOrderCode(), ex.toString());
            }
        }
        return new Result(batch.size(), failed);
    }

    private List<PaymentTransaction> candidates(Instant createdAfter, Instant createdBefore, long afterId, int limit) {
        return tx.required(() -> paymentRepository.findReconciliationCandidates(PaymentMethod.PAYOS,
                PaymentStatus.PENDING, createdAfter, createdBefore, afterId, PageRequest.of(0, limit)));
    }
}
