package com.amomeal.marketplace.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timer for {@link PaymentReconciliationService} — a Spring-only job (Django has none). Gated by
 * {@code app.payment.reconciliation.enabled} (default true; tests disable it and call the
 * service directly).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.interval:PT60S}",
            initialDelayString = "${app.payment.reconciliation.interval:PT60S}")
    public void reconcilePendingPayments() {
        try {
            PaymentReconciliationService.Result result = reconciliationService.reconcileOnce();
            if (result.examined() > 0) {
                log.info("PayOS reconciliation: examined {} pending payment(s), {} failed", result.examined(), result.failed());
            }
        } catch (RuntimeException ex) {
            // A scheduled method that throws is dropped silently; log instead of going quiet.
            log.error("PayOS reconciliation tick failed", ex);
        }
    }
}
