package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.config.StockProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Spring equivalent of Django's ONE dish-side Celery task,
 * ../../backend/dish/tasks.py::release_expired_stock_holds.
 *
 * <p><b>Interval:</b> every 30 seconds, read off
 * ../../backend/marketplace/celery.py:
 * <pre>
 * app.conf.beat_schedule = {
 *     "release-expired-stock-holds": {
 *         "task": "dish.tasks.release_expired_stock_holds",
 *         "schedule": 30.0,
 *     },
 * }
 * </pre>
 * CLAUDE.md §6 records the decision: only two Celery tasks exist project-wide,
 * so they become {@code @Scheduled}/{@code @Async} — no message broker.
 * {@code fixedDelay} (not {@code fixedRate}) so a slow tick cannot stack up
 * concurrent sweeps, which Celery Beat also avoids for a single worker.
 *
 * <h2>PORT-NOTE — what this scheduler does NOT do yet</h2>
 * Djangos task has three steps:
 * <ol>
 *   <li><b>release the held quantity</b> — implemented here, in full
 *       ({@link StockReservationService#releaseExpiredHolds()}); this is the
 *       entire dish-owned half and the part that must be correct for inventory;</li>
 *   <li><b>cancel the corresponding order</b> if it never got confirmed
 *       (DRAFT/PENDING -&gt; CANCELLED) and expire its
 *       {@code voucher.AppliedVoucher} rows — belongs to `order`/`voucher`,
 *       neither of which is ported yet;</li>
 *   <li><b>enqueue an idempotent customer notification</b>
 *       ({@code order.tasks.send_order_notification_task}, the other Celery
 *       task) — likewise `order`-owned.</li>
 * </ol>
 * Steps 2 and 3 are deliberately left as a single, explicit extension point
 * rather than stubbed: {@link ExpiredOrderHandler}. The `order` port supplies
 * one bean implementing it and the whole Django task is complete, with no change
 * needed here. Until then the sweep logs the uids it would have cancelled, so an
 * operator can still see them.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.stock", name = "sweep-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class StockHoldSweepScheduler {

    private final StockReservationService stockReservationService;
    private final ExpiredOrderHandler expiredOrderHandler;
    private final StockProperties properties;

    /** Django beat schedule: every 30.0 seconds. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void releaseExpiredStockHolds() {
        try {
            sweepOnce();
        } catch (RuntimeException ex) {
            // A scheduled method that throws is silently dropped by Spring's scheduler
            // and (unlike Celery) never retried — log loudly instead of going quiet.
            log.error("Stock hold sweep failed", ex);
        }
    }

    /**
     * The sweep body, separated from the scheduling annotation so tests can drive
     * exactly one tick deterministically instead of waiting on the clock.
     *
     * @return how many orders the handler reported as cancelled (Django's return value)
     */
    public int sweepOnce() {
        Set<UUID> expiredOrderUids = stockReservationService.releaseExpiredHolds();
        if (expiredOrderUids.isEmpty()) {
            return 0;
        }
        int cancelledCount = expiredOrderHandler.cancelExpiredOrders(expiredOrderUids);
        if (cancelledCount > 0) {
            log.info("Stock hold sweep: cancelled {} expired order(s)", cancelledCount);
        }
        return cancelledCount;
    }

    /** Exposed for diagnostics/tests — the configured batch size per tick. */
    public int batchSize() {
        return properties.getSweepBatchSize();
    }
}
