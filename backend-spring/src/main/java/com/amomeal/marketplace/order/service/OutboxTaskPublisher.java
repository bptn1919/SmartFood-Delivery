package com.amomeal.marketplace.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spring's "broker": Django's {@code current_app.tasks[event.task_name].apply_async(kwargs=
 * event.payload)} in {@code order/outbox.py::publish_event}. Resolves the task name to the
 * {@code @Async} bean method and hands the payload to it. Throws synchronously when the
 * hand-off itself fails (unknown task, bad payload, executor rejecting the task —
 * {@code TaskRejectedException}); the returned future completes when the task has finished.
 */
@Component
@RequiredArgsConstructor
public class OutboxTaskPublisher {

    /** Django's task path, kept verbatim as the stored {@code task_name}. */
    public static final String NOTIFICATION_TASK = "order.tasks.send_order_notification_task";

    private final OrderNotificationService notificationService;

    public CompletableFuture<String> publish(String taskName, Map<String, Object> payload) {
        if (NOTIFICATION_TASK.equals(taskName)) {
            return notificationService.sendOrderNotificationTask(
                    String.valueOf(payload.get("idempotency_key")),
                    UUID.fromString(String.valueOf(payload.get("order_uid"))),
                    String.valueOf(payload.get("event_type")));
        }
        // Django: KeyError from current_app.tasks[...] — recorded as a failed attempt.
        throw new IllegalArgumentException("Unknown outbox task '" + taskName + "'");
    }
}
