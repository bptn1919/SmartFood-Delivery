package com.amomeal.marketplace.dish.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * Port of {@code _RedisCircuitBreaker} (../../backend-edit/.../dish/services/stock_reservation.py,
 * commit 9939179) — a minimal thread-safe circuit breaker around the stock Redis.
 *
 * <ul>
 *   <li>CLOSED: every call goes to Redis; {@code failMax} consecutive failures open it.</li>
 *   <li>OPEN: {@link #allow()} is false (callers skip Redis instantly instead of each paying a
 *       connect timeout), except one probe call per {@code resetTimeout} (half-open: the probe
 *       re-arms the timer so concurrent callers keep skipping Redis meanwhile).</li>
 *   <li>A successful call closes it again.</li>
 * </ul>
 * It also remembers when the current run of failures started, so the caller can resync stale
 * counters once Redis is reachable again ({@link #recordSuccess()} hands that instant out exactly
 * once per outage). In-process like Django's module global: one breaker per JVM (a singleton
 * owned by {@link StockRedisGateway}); several app instances each keep their own view, which is
 * fine — a stray extra probe costs one short timeout, nothing more.
 */
@Slf4j
public class StockRedisCircuitBreaker {

    private final int failMax;
    private final long resetTimeoutNanos;
    private final LongSupplier monotonicNanos;
    private final Clock clock;

    private int failures;
    private boolean open;
    private long nextProbeAtNanos;
    private Instant firstFailureAt;

    public StockRedisCircuitBreaker(int failMax, double resetTimeoutSeconds) {
        this(failMax, resetTimeoutSeconds, System::nanoTime, Clock.systemUTC());
    }

    /** Test constructor with an injectable monotonic clock (Django: {@code time.monotonic}). */
    public StockRedisCircuitBreaker(int failMax, double resetTimeoutSeconds, LongSupplier monotonicNanos, Clock clock) {
        this.failMax = Math.max(1, failMax);
        this.resetTimeoutNanos = Math.round(resetTimeoutSeconds * 1_000_000_000L);
        this.monotonicNanos = monotonicNanos;
        this.clock = clock;
    }

    public synchronized boolean allow() {
        if (!open) {
            return true;
        }
        long now = monotonicNanos.getAsLong();
        if (now - nextProbeAtNanos >= 0) {
            // Let exactly one caller probe per window; re-arm the timer so concurrent callers
            // keep skipping Redis meanwhile.
            nextProbeAtNanos = now + resetTimeoutNanos;
            return true;
        }
        return false;
    }

    public synchronized void recordFailure() {
        if (firstFailureAt == null) {
            firstFailureAt = clock.instant();
        }
        failures++;
        if (failures >= failMax) {
            if (!open) {
                log.error("Stock Redis circuit OPEN after {} consecutive failures — "
                        + "reservations fall back to Postgres row locks", failures);
            }
            open = true;
            nextProbeAtNanos = monotonicNanos.getAsLong() + resetTimeoutNanos;
        }
    }

    /**
     * @return when the outage started if this success ends one (exactly once per outage), else
     *         null. The caller must then resync counters.
     */
    public synchronized Instant recordSuccess() {
        Instant outageStartedAt = firstFailureAt;
        if (open) {
            log.warn("Stock Redis circuit CLOSED — Redis reachable again");
        }
        failures = 0;
        open = false;
        firstFailureAt = null;
        return outageStartedAt;
    }

    public synchronized boolean isOpen() {
        return open;
    }

    /** Operator/test hook: forget any outage (CLOSED, no pending resync). */
    public synchronized void reset() {
        failures = 0;
        open = false;
        firstFailureAt = null;
    }
}
