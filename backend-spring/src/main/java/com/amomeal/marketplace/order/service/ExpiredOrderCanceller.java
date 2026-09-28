package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.repository.OrderAppliedVoucherRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The body of the {@code with transaction.atomic():} block inside
 * ../../backend/dish/tasks.py::release_expired_stock_holds's per-order loop.
 *
 * <p>Its own bean + {@code REQUIRES_NEW} because Django commits each order's
 * cancellation independently: one order failing must not roll back the others,
 * and the notification for an order must only be published after THAT order's
 * cancellation has committed (CLAUDE.md §8b) — its outbox row is written in the
 * same transaction and dispatched after commit.
 */
@Component
@RequiredArgsConstructor
public class ExpiredOrderCanceller {

    private final OrderRepository orderRepository;
    private final OrderAppliedVoucherRepository appliedVoucherRepository;
    private final OutboxService outboxService;

    /**
     * @return true if the order was transitioned to CANCELLED by this call
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelIfStillUnconfirmed(UUID orderUid) {
        // Django: Order.objects.select_for_update().filter(uid=order_uid).first()
        Order order = orderRepository.findForUpdateByUid(orderUid).orElse(null);
        if (order == null
                || (order.getStatus() != OrderStatus.DRAFT && order.getStatus() != OrderStatus.PENDING)) {
            // Already confirmed (payment webhook won) or cancelled by another path.
            return false;
        }
        // PORT-NOTE: a direct status write, NOT routed through OrderStateMachine —
        // Django's sweep does order.status = CANCELLED; save(update_fields=["status"]).
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.saveAndFlush(order);

        // Django: AppliedVoucher(order=order, status in [RESERVED, USED]) -> EXPIRED.
        appliedVoucherRepository.expireForOrder(orderUid);

        // backend-edit 9939179: enqueue_order_notification (transactional outbox) instead of a
        // direct task call. Enqueued INSIDE this order's transaction (Django calls it right
        // after its atomic block, under autocommit): the event commits with the cancellation
        // or not at all, and is published only after commit.
        outboxService.enqueueOrderNotification("order-expired:" + orderUid, orderUid,
                OrderExpiredOrderHandler.EVENT_CANCELLED_EXPIRED);
        return true;
    }
}
