package com.amomeal.marketplace.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring equivalent of backend-edit 9939179's Celery Beat entry
 * {@code "dispatch-pending-outbox-events": order.tasks.dispatch_pending_outbox_events, every 30.0 s}.
 * {@code fixedDelay} so a slow tick never overlaps the next (the {@code SKIP LOCKED} claim would
 * make overlap safe anyway, also across several app instances). Off in tests
 * ({@code app.order.outbox.dispatch-enabled=false}); they call {@link OutboxService#dispatchPending()}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.order.outbox", name = "dispatch-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxDispatchScheduler {

    private final OutboxService outboxService;

    @Scheduled(fixedDelayString = "${app.order.outbox.dispatch-interval:PT30S}",
            initialDelayString = "${app.order.outbox.dispatch-interval:PT30S}")
    public void dispatchPendingOutboxEvents() {
        try {
            outboxService.dispatchPending();
        } catch (RuntimeException ex) {
            // A throwing @Scheduled method is not retried by Spring — log loudly instead.
            log.error("Outbox dispatch tick failed", ex);
        }
    }
}
