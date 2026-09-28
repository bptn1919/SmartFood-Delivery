package com.amomeal.marketplace.order.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Transactional-outbox settings ({@code app.order.outbox.*}). Defaults are the constants Django
 * hardcodes in backend-edit commit 9939179 ({@code order/outbox.py}: {@code MAX_ATTEMPTS = 10},
 * {@code _BACKOFF_BASE_SECONDS = 5}, {@code _BACKOFF_MAX_SECONDS = 300},
 * {@code SWEEP_BATCH_SIZE = 100}; {@code marketplace/celery.py}: dispatch every 30 s).
 */
@ConfigurationProperties(prefix = "app.order.outbox")
@Getter
@Setter
public class OutboxProperties {

    /** Whether the {@code @Scheduled} dispatcher of pending events runs (Django Celery Beat). */
    private boolean dispatchEnabled = true;

    /** Django beat schedule: {@code dispatch-pending-outbox-events} every 30.0 s. */
    private Duration dispatchInterval = Duration.ofSeconds(30);

    /** Django {@code MAX_ATTEMPTS}: give up (status FAILED) after this many failed attempts. */
    private int maxAttempts = 10;

    /** Django {@code _BACKOFF_BASE_SECONDS}. */
    private long backoffBaseSeconds = 5;

    /** Django {@code _BACKOFF_MAX_SECONDS}. */
    private long backoffMaxSeconds = 300;

    /** Django {@code SWEEP_BATCH_SIZE}: events per dispatcher tick. */
    private int sweepBatchSize = 100;

    /**
     * Spring-only: how long a handed-off event stays invisible to the dispatcher while its async
     * task runs (the in-process executor is not a durable broker — see {@code OutboxService}).
     * Must exceed the notification task's own retry window (4 attempts x 30 s).
     */
    private Duration inFlightLease = Duration.ofMinutes(5);
}
