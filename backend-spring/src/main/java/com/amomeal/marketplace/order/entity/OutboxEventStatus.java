package com.amomeal.marketplace.order.entity;

/** Django {@code OutboxEvent.Status} (backend-edit 9939179). */
public enum OutboxEventStatus {
    /** Waiting to be published (or, in this port, handed off and not yet acknowledged). */
    PENDING,
    /** Django: "Sent to broker". Here: the async task ran to a final answer (see OutboxService). */
    SENT,
    /** Gave up after {@code app.order.outbox.max-attempts} attempts. */
    FAILED
}
