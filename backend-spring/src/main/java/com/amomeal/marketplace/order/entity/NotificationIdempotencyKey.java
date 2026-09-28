package com.amomeal.marketplace.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Mirrors ../../backend/order/models.py::NotificationIdempotencyKey, including
 * its docstring: a dedup guard for the async notification task.
 *
 * <p>Celery/Redis delivery is at-least-once, so a task can be redelivered after
 * a worker crash or broker retry; this port's replacement
 * ({@code @Async} + {@code @Retryable}, CLAUDE.md §6) has exactly the same
 * property, because {@code @Retryable} re-invokes the method body. A task claims
 * a key by INSERTing it, and the DB-level UNIQUE constraint on {@code key} is
 * what makes the claim atomic even under genuinely concurrent redelivery — the
 * <i>database transaction</i>, not application code, decides who wins.
 *
 * <p>See {@code OrderNotificationService} for the claim/release protocol.
 */
@Entity
@Table(name = "notification_idempotency_key", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_idempotency_key", columnNames = "key")
}, indexes = {
        @Index(name = "idx_notification_idempotency_task_name", columnList = "task_name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationIdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key", nullable = false, length = 255)
    private String key;

    @Column(name = "task_name", nullable = false, length = 100)
    private String taskName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
