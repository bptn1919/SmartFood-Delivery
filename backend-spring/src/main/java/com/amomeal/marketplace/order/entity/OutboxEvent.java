package com.amomeal.marketplace.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Mirrors backend-edit commit 9939179's {@code order/models.py::OutboxEvent} (migration
 * 0016_outboxevent; Flyway V19 here): a transactional outbox for tasks that must not be lost
 * when the async hand-off fails.
 *
 * <p>The row is written in the SAME transaction as the business change that caused it, so the
 * two commit or roll back together. Publishing happens after commit (best effort) and, when that
 * fails, later from the {@code @Scheduled} dispatcher ({@code OutboxDispatchScheduler}, Django's
 * {@code dispatch_pending_outbox_events} beat task). Delivery is therefore at-least-once; the
 * consumers are idempotent ({@link NotificationIdempotencyKey}).
 *
 * <p>Rows are only created through {@code OutboxEventRepository.insertIfAbsent} (native
 * {@code ON CONFLICT DO NOTHING}) and mutated through conditional {@code WHERE status='PENDING'}
 * updates, like Django's {@code filter(pk=..., status=PENDING).update(...)}.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "task_name", nullable = false, length = 200)
    private String taskName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private OutboxEventStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", nullable = false, columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;
}
