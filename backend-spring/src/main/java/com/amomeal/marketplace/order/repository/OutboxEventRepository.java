package com.amomeal.marketplace.order.repository;

import com.amomeal.marketplace.order.entity.OutboxEvent;
import com.amomeal.marketplace.order.entity.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Django {@code OutboxEvent.objects.get_or_create(idempotency_key=..., defaults=...)}: the
     * first enqueue of a key wins. {@code ON CONFLICT DO NOTHING} instead of catching a unique
     * violation, because in Postgres a failed INSERT would poison the caller's transaction — and
     * the whole point is to enqueue INSIDE the business transaction.
     *
     * @return 1 if this call created the row, 0 if the key already existed
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO outbox_event (idempotency_key, task_name, payload, status, attempts,
                                      next_attempt_at, last_error, created_at)
            VALUES (:key, :taskName, CAST(:payload AS jsonb), 'PENDING', 0, now(), '', now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("key") String key, @Param("taskName") String taskName, @Param("payload") String payloadJson);

    Optional<OutboxEvent> findByIdempotencyKey(String idempotencyKey);

    Optional<OutboxEvent> findByIdAndStatus(Long id, OutboxEventStatus status);

    /**
     * Django {@code dispatch_pending_outbox_events}: {@code select_for_update(skip_locked=True)
     * .filter(status=PENDING, next_attempt_at__lte=now()).order_by("id")[:SWEEP_BATCH_SIZE]} —
     * overlapping sweeps (or several app instances) never publish the same row at once.
     */
    @Query(nativeQuery = true, value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEvent> lockDueEvents(@Param("limit") int limit);

    /** Django publish_event's failure branch: conditional on PENDING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.attempts = :attempts, e.lastError = :lastError, "
            + "e.nextAttemptAt = :nextAttemptAt, e.status = :newStatus "
            + "WHERE e.id = :id AND e.status = com.amomeal.marketplace.order.entity.OutboxEventStatus.PENDING")
    int recordFailedAttempt(@Param("id") Long id, @Param("attempts") int attempts, @Param("lastError") String lastError,
                            @Param("nextAttemptAt") Instant nextAttemptAt, @Param("newStatus") OutboxEventStatus newStatus);

    /** Django publish_event's success branch: {@code status=SENT, attempts+1, sent_at=now}, conditional on PENDING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = com.amomeal.marketplace.order.entity.OutboxEventStatus.SENT, "
            + "e.attempts = :attempts, e.sentAt = :sentAt "
            + "WHERE e.id = :id AND e.status = com.amomeal.marketplace.order.entity.OutboxEventStatus.PENDING")
    int markSent(@Param("id") Long id, @Param("attempts") int attempts, @Param("sentAt") Instant sentAt);

    /** Spring-only in-flight lease: hide a handed-off event from the dispatcher until {@code until}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.nextAttemptAt = :until "
            + "WHERE e.id = :id AND e.status = com.amomeal.marketplace.order.entity.OutboxEventStatus.PENDING")
    int lease(@Param("id") Long id, @Param("until") Instant until);
}
