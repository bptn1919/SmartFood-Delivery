package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.dish.service.ExpiredOrderHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * The real implementation of {@code dish}'s {@link ExpiredOrderHandler} seam —
 * steps 2 and 3 of ../../backend/dish/tasks.py::release_expired_stock_holds
 * (step 1, releasing the held stock back to Redis and flipping the ledger rows
 * RESERVED -&gt; EXPIRED, stays in {@code dish}'s
 * {@code StockReservationService.releaseExpiredHolds}, which runs first):
 *
 * <ol start="2">
 *   <li>for each touched order still DRAFT/PENDING (row-locked): -&gt; CANCELLED,
 *       and its RESERVED/USED {@code AppliedVoucher} rows -&gt; EXPIRED;</li>
 *   <li>enqueue {@code send_order_notification_task(idempotency_key=
 *       "order-expired:{uid}", event_type="CANCELLED_EXPIRED")} — through the
 *       transactional outbox since backend-edit 9939179 ({@link OutboxService}).</li>
 * </ol>
 *
 * <p>An order already CONFIRMED_SYSTEM or later is left untouched — that is the
 * only guard Django has at the order level against the sweep racing a payment
 * webhook (see ../../backend/ORDER_FLOW_WALKTHROUGH.md §3.2). The stock-level
 * race is handled one layer down by {@code release(expired=true)} backing off.
 *
 * <p>{@code @Primary} displaces {@code dish}'s {@code LoggingExpiredOrderHandler}
 * (see that seam's javadoc for why {@code @ConditionalOnMissingBean} is not used).
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpiredOrderHandler implements ExpiredOrderHandler {

    static final String EVENT_CANCELLED_EXPIRED = "CANCELLED_EXPIRED";

    private final ExpiredOrderCanceller canceller;

    @Override
    public int cancelExpiredOrders(Set<UUID> orderUids) {
        int cancelledCount = 0;
        for (UUID orderUid : orderUids) {
            if (!canceller.cancelIfStillUnconfirmed(orderUid)) {
                continue;
            }
            // The CANCELLED_EXPIRED notification was enqueued through the outbox inside the
            // canceller's transaction (backend-edit 9939179).
            cancelledCount++;
        }
        if (cancelledCount > 0) {
            log.info("Stock hold sweep: cancelled {} expired order(s)", cancelledCount);
        }
        return cancelledCount;
    }
}
