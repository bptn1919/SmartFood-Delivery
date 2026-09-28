package com.amomeal.marketplace.dish.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of Django's {@code _RedisCircuitBreaker} semantics (backend-edit 9939179), on a fake clock. */
class StockRedisCircuitBreakerTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final Instant wallClock = Instant.parse("2026-09-25T10:00:00Z");
    private final StockRedisCircuitBreaker breaker = new StockRedisCircuitBreaker(3, 10.0, nanos::get,
            Clock.fixed(wallClock, ZoneOffset.UTC));

    private void advanceSeconds(double s) {
        nanos.addAndGet(Math.round(s * 1_000_000_000L));
    }

    @Test
    void closed_allowsEverything_andOpensOnlyAfterFailMaxConsecutiveFailures() {
        assertThat(breaker.allow()).isTrue();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allow()).isTrue();

        breaker.recordFailure(); // 3rd consecutive
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allow()).isFalse();
        assertThat(breaker.allow()).isFalse();
    }

    @Test
    void aSuccessInBetween_resetsTheConsecutiveCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void open_halfOpensAfterTheCooldown_withExactlyOneProbePerWindow() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        advanceSeconds(9.9);
        assertThat(breaker.allow()).isFalse();

        advanceSeconds(0.2); // past the 10 s reset window
        assertThat(breaker.allow()).isTrue();  // the probe
        assertThat(breaker.allow()).isFalse(); // concurrent callers keep skipping Redis

        // the probe failed: still open, next probe one window later
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
        advanceSeconds(5);
        assertThat(breaker.allow()).isFalse();
        advanceSeconds(5.1);
        assertThat(breaker.allow()).isTrue();
    }

    @Test
    void recordSuccess_closes_andReportsTheOutageStartExactlyOnce() {
        assertThat(breaker.recordSuccess()).isNull(); // no outage in progress

        breaker.recordFailure(); // outage starts here (even before the breaker opens)
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.recordSuccess()).isEqualTo(wallClock);
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allow()).isTrue();
        assertThat(breaker.recordSuccess()).isNull(); // resync is triggered only once per outage
    }
}
