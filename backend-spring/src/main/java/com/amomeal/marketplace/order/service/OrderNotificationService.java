package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spring equivalent of Django's second (and last) Celery task,
 * ../../backend/order/tasks.py::send_order_notification_task
 * ({@code @shared_task(bind=True, max_retries=3, default_retry_delay=30)}), per
 * CLAUDE.md §6: {@code @Async} replaces {@code .delay()}, {@code @Retryable}
 * replaces {@code self.retry(...)} — no broker.
 *
 * <ul>
 *   <li>{@code maxAttempts = 4}: Celery's {@code max_retries=3} means 3 retries
 *       AFTER the first run, i.e. 4 executions total.</li>
 *   <li>Delay: 30 s ({@code default_retry_delay=30}), overridable via
 *       {@code app.order.notification.retry-delay-ms} so tests need not wait.</li>
 * </ul>
 *
 * <h2>Idempotency (the reason this task has any logic at all)</h2>
 * Exactly Django's protocol, in the same order:
 * <ol>
 *   <li>Claim {@code idempotencyKey} via {@link NotificationClaimService#claim}
 *       (DB UNIQUE constraint). Not created -&gt; {@code "skipped_duplicate"}, no
 *       side effect.</li>
 *   <li>Load the order; no order / no owner / no email -&gt; {@code "no_recipient"}
 *       (claim KEPT — Django returns normally here).</li>
 *   <li>Send -&gt; {@code "sent"}.</li>
 *   <li>Any exception: <b>release the claim first</b>, then rethrow so
 *       {@code @Retryable} re-runs the body — which re-claims the now-free key. A
 *       redelivery after a SUCCESSFUL send finds the key and is a guaranteed
 *       no-op; that is what stops a retry from double-sending.</li>
 * </ol>
 *
 * <p>Proxy ordering: Spring's async advisor is registered before existing
 * advisors, so the call hops to the executor thread first and the retry loop
 * then runs inside that thread — a retry never blocks the HTTP/sweep caller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationService {

    public static final String TASK_NAME = "send_order_notification_task";

    private final NotificationClaimService claimService;
    private final OrderRepository orderRepository;
    private final OrderEmailService emailService;

    @Async
    @Retryable(retryFor = Exception.class, maxAttempts = 4,
            backoff = @Backoff(delayExpression = "${app.order.notification.retry-delay-ms:30000}"))
    public CompletableFuture<String> sendOrderNotificationTask(String idempotencyKey, UUID orderUid, String eventType) {
        return CompletableFuture.completedFuture(runOnce(idempotencyKey, orderUid, eventType));
    }

    /**
     * One execution of the task body, without the async/retry wrapping — the unit
     * the retry loop repeats. Public so the idempotency protocol is testable
     * synchronously.
     */
    public String runOnce(String idempotencyKey, UUID orderUid, String eventType) {
        if (!claimService.claim(idempotencyKey, TASK_NAME)) {
            log.info("Notification '{}' already processed, skipping", idempotencyKey);
            return "skipped_duplicate";
        }
        try {
            Order order = orderRepository.findDetailedByUid(orderUid).orElse(null);
            if (order == null || order.getOwner() == null || order.getOwner().getEmail() == null
                    || order.getOwner().getEmail().isEmpty()) {
                return "no_recipient";
            }
            emailService.sendOrderStatusEmail(order.getOwner(), order, eventType);
            return "sent";
        } catch (RuntimeException ex) {
            // Release the claim so a retry (or a legitimate future redelivery) can
            // actually attempt delivery again instead of being skipped forever.
            claimService.release(idempotencyKey);
            throw ex;
        }
    }

    /** After the 4th failed attempt (Celery: MaxRetriesExceededError) — claim is already released. */
    @Recover
    public CompletableFuture<String> giveUp(Exception ex, String idempotencyKey, UUID orderUid, String eventType) {
        log.error("Notification '{}' for order {} failed after all retries: {}", idempotencyKey, orderUid, ex.toString());
        return CompletableFuture.completedFuture("failed");
    }
}
