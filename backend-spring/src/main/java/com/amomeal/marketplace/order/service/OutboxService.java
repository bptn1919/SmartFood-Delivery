package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.config.OutboxProperties;
import com.amomeal.marketplace.order.entity.OutboxEvent;
import com.amomeal.marketplace.order.entity.OutboxEventStatus;
import com.amomeal.marketplace.order.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transactional outbox — port of backend-edit commit 9939179's {@code order/outbox.py} plus the
 * body of {@code order/tasks.py::dispatch_pending_outbox_events}.
 *
 * <p><b>Why</b> (Django's docstring): a direct async call in the middle of confirming a payment
 * can fail and abort the rest of the confirmation for a side effect (an email) that has nothing
 * to do with whether the payment is valid; and a publish that happens BEFORE the surrounding
 * transaction commits can announce something that then rolls back.
 *
 * <p><b>How</b>: {@link #enqueueTask} records the intent in Postgres in the SAME transaction as
 * the business change (it joins the caller's transaction), and publishes only after commit,
 * swallowing hand-off errors. Whatever could not be published stays PENDING and is retried by
 * {@link #dispatchPending()} ({@code OutboxDispatchScheduler}, every 30 s) with exponential
 * backoff ({@code min(300, 5 * 2^(attempts-1))} s), until {@code max-attempts} (10) → FAILED.
 * A rolled-back transaction leaves no row and never publishes.
 *
 * <p><b>Spring adaptation (deliberate):</b> Django's "publish" hands the task to RabbitMQ, a
 * durable broker, so SENT means "the broker has it". Here the "broker" is the in-process
 * {@code @Async} executor, which is NOT durable — a crash after the hand-off would lose the task.
 * So a successful hand-off only leases the row ({@code next_attempt_at = now + in-flight-lease},
 * still PENDING) and the SENT / failed-attempt bookkeeping is done when the task's future
 * completes: {@code "sent"}/{@code "skipped_duplicate"}/{@code "no_recipient"} → SENT
 * ({@code attempts+1}, {@code sent_at}); an exception or {@code "failed"} (the notification
 * task's own 4 {@code @Retryable} attempts exhausted) → a failed attempt with backoff. A crash
 * mid-flight is healed by the dispatcher once the lease expires. Delivery stays at-least-once
 * (inline dispatch and the dispatcher can both publish); the consumer is idempotent
 * ({@code NotificationIdempotencyKey}), so the email itself goes out exactly once.
 */
@Service
@Slf4j
public class OutboxService {

    private final OutboxEventRepository repository;
    private final OutboxTaskPublisher publisher;
    private final OutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate required;

    public OutboxService(OutboxEventRepository repository, OutboxTaskPublisher publisher, OutboxProperties properties,
                         ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
    }

    /** Django {@code _backoff(attempts)}: {@code min(300, 5 * 2 ** max(0, attempts - 1))} seconds. */
    public Duration backoff(int attempts) {
        long seconds = properties.getBackoffBaseSeconds() * (1L << Math.min(30, Math.max(0, attempts - 1)));
        return Duration.ofSeconds(Math.min(properties.getBackoffMaxSeconds(), seconds));
    }

    // =====================================================================
    // enqueue
    // =====================================================================

    /**
     * Django {@code enqueue_task(task_name, payload, idempotency_key)}: record a task to run and
     * publish it after the current transaction commits (its own transaction if there is none).
     * Safe to call with the executor unavailable. {@code idempotencyKey} de-duplicates the
     * intent itself: enqueueing the same key twice (webhook redelivery) keeps the first row.
     */
    @Transactional
    public void enqueueTask(String taskName, Map<String, Object> payload, String idempotencyKey) {
        repository.insertIfAbsent(idempotencyKey, taskName, objectMapper.writeValueAsString(payload));
        OutboxEvent event = repository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        if (event.getStatus() != OutboxEventStatus.PENDING) {
            return; // already published (or given up on) — nothing more to do
        }
        long eventId = event.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchAfterCommit(eventId);
            }
        });
    }

    /** Django {@code enqueue_order_notification(idempotency_key, order_uid, event_type)}. */
    @Transactional
    public void enqueueOrderNotification(String idempotencyKey, UUID orderUid, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotency_key", idempotencyKey);
        payload.put("order_uid", orderUid.toString());
        payload.put("event_type", eventType);
        enqueueTask(OutboxTaskPublisher.NOTIFICATION_TASK, payload, idempotencyKey);
    }

    /** Django {@code _dispatch_after_commit}: re-read (the dispatcher may have published it meanwhile). */
    private void dispatchAfterCommit(long eventId) {
        try {
            // afterCommit still has the committed transaction's resources bound: use a new one.
            requiresNew.executeWithoutResult(status ->
                    repository.findByIdAndStatus(eventId, OutboxEventStatus.PENDING).ifPresent(this::publishEvent));
        } catch (RuntimeException ex) {
            // Never let a publish problem surface as an error of an already-committed business call.
            log.error("Outbox inline dispatch of event {} failed; the dispatcher will retry", eventId, ex);
        }
    }

    // =====================================================================
    // publish / dispatch
    // =====================================================================

    /**
     * Django {@code publish_event(event)}: try to hand one outbox event to the executor. Never
     * throws. Returns true if the hand-off succeeded. Runs in the caller's transaction (the
     * dispatcher holds the row lock; the inline path opens its own).
     */
    @Transactional
    public boolean publishEvent(OutboxEvent event) {
        long id = event.getId();
        int attemptsBefore = event.getAttempts();
        String key = event.getIdempotencyKey();
        // Lease BEFORE the hand-off: this UPDATE row-locks the event until the caller commits, so
        // the completion bookkeeping below (another thread) always lands after it and can never
        // be overwritten by it.
        repository.lease(id, Instant.now().plus(properties.getInFlightLease()));
        CompletableFuture<String> future;
        try {
            future = publisher.publish(event.getTaskName(), event.getPayload());
        } catch (RuntimeException ex) { // executor rejected, unknown task, bad payload...
            recordFailure(id, attemptsBefore, key, ex);
            return false;
        }
        // ...Async: if the task already finished, a plain whenComplete would run the callback
        // right here, inside the transaction that holds this row's lock -> self-deadlock.
        future.whenCompleteAsync((result, error) -> {
            try {
                requiresNew.executeWithoutResult(status -> acknowledge(id, attemptsBefore, key, result, error));
            } catch (RuntimeException ex) {
                log.error("Outbox bookkeeping for event {} failed; it stays PENDING until its lease expires", key, ex);
            }
        });
        return true;
    }

    private void acknowledge(long id, int attemptsBefore, String key, String result, Throwable error) {
        if (error == null && !"failed".equals(result)) {
            repository.markSent(id, attemptsBefore + 1, Instant.now());
            return;
        }
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        recordFailure(id, attemptsBefore, key, cause != null ? cause
                : new IllegalStateException("task gave up after all its retries"));
    }

    private void recordFailure(long id, int attemptsBefore, String key, Throwable ex) {
        int attempts = attemptsBefore + 1;
        boolean gaveUp = attempts >= properties.getMaxAttempts();
        String error = (ex.getClass().getSimpleName() + ": " + ex.getMessage());
        if (error.length() > 2000) {
            error = error.substring(0, 2000);
        }
        repository.recordFailedAttempt(id, attempts, error, Instant.now().plus(backoff(attempts)),
                gaveUp ? OutboxEventStatus.FAILED : OutboxEventStatus.PENDING);
        if (gaveUp) {
            log.error("Outbox event {} not published (attempt {}/{}): {}", key, attempts, properties.getMaxAttempts(), error);
        } else {
            log.warn("Outbox event {} not published (attempt {}/{}): {}", key, attempts, properties.getMaxAttempts(), error);
        }
    }

    /**
     * Body of Django's {@code dispatch_pending_outbox_events}: in one transaction, lock due
     * PENDING rows with {@code FOR UPDATE SKIP LOCKED} (id order, batch size), publish each; if
     * the very first publish fails the executor is most likely still unavailable — stop instead
     * of failing every row (the failed row's backoff already moved it out of the next sweep).
     *
     * @return how many events were handed off
     */
    public int dispatchPending() {
        Integer published = required.execute(status -> {
            List<OutboxEvent> due = repository.lockDueEvents(Math.max(1, properties.getSweepBatchSize()));
            int count = 0;
            for (OutboxEvent event : due) {
                if (publishEvent(event)) {
                    count++;
                } else if (count == 0) {
                    break;
                }
            }
            if (!due.isEmpty()) {
                log.info("Outbox sweep: published {} of {} due event(s)", count, due.size());
            }
            return count;
        });
        return published == null ? 0 : published;
    }
}
