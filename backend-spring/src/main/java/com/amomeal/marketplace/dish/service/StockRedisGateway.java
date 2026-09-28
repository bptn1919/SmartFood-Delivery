package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.config.StockProperties;
import com.amomeal.marketplace.dish.config.StockRedisClient;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.StockReservationException;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.StockReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Every stock-Redis touch of {@link StockReservationService} goes through here — the
 * "Redis access — every call goes through the breaker" section of Django's
 * stock_reservation.py (backend-edit commit 9939179): {@code _note_redis_failure},
 * {@code _note_redis_success}, {@code _resync_counters_after_outage}, {@code _redis_hold},
 * {@code invalidate_counter}, {@code _redis_credit}.
 *
 * <p>Redis is a cache/accelerator here, never a hard dependency: any Redis error (connection
 * refused, 0.3 s timeout, ...) is recorded on the {@link StockRedisCircuitBreaker} and reported
 * to the caller as "Redis unavailable" so it can use the Postgres path; a genuine
 * out-of-stock answer from Redis is still an error ({@link StockReservationException}).
 *
 * <p>A separate bean (not inside {@code StockReservationService}) so {@link DishInventoryService}
 * can invalidate a counter after a chef edit without a circular dependency.
 */
@Component
@Slf4j
public class StockRedisGateway {

    /** Django {@code _RESYNC_MARGIN}: covers requests that were in flight when the outage began. */
    static final Duration RESYNC_MARGIN = Duration.ofSeconds(60);
    /** Django {@code _RESYNC_KEY_BATCH}. */
    static final int RESYNC_KEY_BATCH = 500;

    private final StockRedisClient redis;
    private final StockReservationRepository reservationRepository;
    private final DishAvailabilityRepository availabilityRepository;
    private final StockRedisCircuitBreaker breaker;

    public StockRedisGateway(StockRedisClient redis, StockReservationRepository reservationRepository,
                             DishAvailabilityRepository availabilityRepository, StockProperties properties) {
        this.redis = redis;
        this.reservationRepository = reservationRepository;
        this.availabilityRepository = availabilityRepository;
        this.breaker = new StockRedisCircuitBreaker(properties.getRedisBreakerFailMax(),
                properties.getRedisBreakerResetSeconds());
    }

    public StockRedisCircuitBreaker breaker() {
        return breaker;
    }

    // ------------------------------------------------------------------
    // breaker bookkeeping
    // ------------------------------------------------------------------

    private void noteFailure(RuntimeException ex, String action) {
        breaker.recordFailure();
        log.warn("Stock Redis unavailable during {} ({}: {})", action, ex.getClass().getSimpleName(), ex.getMessage());
    }

    /**
     * @return true if this success ended an outage and stale counters were just dropped (so any
     *         earlier read of a counter is out of date).
     */
    private boolean noteSuccess() {
        Instant outageStartedAt = breaker.recordSuccess();
        if (outageStartedAt == null) {
            return false;
        }
        resyncCountersAfterOutage(outageStartedAt);
        return true;
    }

    /**
     * Django {@code _resync_counters_after_outage}: holds/releases taken while Redis was
     * unreachable never touched the counters, so any key that survived the outage is stale.
     * Drop the counters of every (dish, date) whose ledger changed since the outage began (or
     * whose availability row changed — a chef edit whose invalidation could not reach Redis);
     * the next reserve() re-seeds them from Postgres. Deleting (rather than rewriting) reuses
     * the existing lazy-seed path and its semantics.
     */
    void resyncCountersAfterOutage(Instant outageStartedAt) {
        try {
            Instant since = outageStartedAt.minus(RESYNC_MARGIN);
            Set<String> keys = new LinkedHashSet<>();
            for (Object[] pair : reservationRepository.findDishDatesUpdatedSince(since)) {
                keys.add(StockRedisClient.stockKey((UUID) pair[0], (LocalDate) pair[1]));
            }
            for (Object[] pair : availabilityRepository.findDishDatesUpdatedSince(since)) {
                keys.add(StockRedisClient.stockKey((UUID) pair[0], (LocalDate) pair[1]));
            }
            List<String> all = new ArrayList<>(keys);
            for (int start = 0; start < all.size(); start += RESYNC_KEY_BATCH) {
                redis.deleteAll(all.subList(start, Math.min(all.size(), start + RESYNC_KEY_BATCH)));
            }
            log.warn("Stock Redis recovered — dropped {} stale counter(s) for re-seeding", all.size());
        } catch (org.springframework.dao.DataAccessException ex) {
            if (isRedisError(ex)) {
                // Redis dropped out again mid-resync; the next success will retry because
                // recordFailure() restarts the outage window.
                noteFailure(ex, "post-outage resync");
            } else {
                log.error("Post-outage stock counter resync failed", ex);
            }
        } catch (RuntimeException ex) {
            log.error("Post-outage stock counter resync failed", ex);
        }
    }

    /** Distinguishes a Redis failure from a Postgres one inside the resync (both are DataAccessExceptions). */
    private static boolean isRedisError(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.startsWith("io.lettuce.") || name.startsWith("org.springframework.data.redis.")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // operations
    // ------------------------------------------------------------------

    /**
     * Django {@code _redis_hold}: try the Redis fast path.
     *
     * @param seed computes {@code DishAvailability - SUM(RESERVED)}; only called on the rare
     *             first touch of this dish/date (it may throw "no availability record")
     * @return true if {@code quantity} was decremented in Redis, false if Redis cannot be used
     *         (breaker open, or the call failed) and the caller must use the Postgres fallback
     * @throws StockReservationException for a genuine out-of-stock answer — that IS a valid
     *         Redis response
     */
    public boolean tryHold(Dish dish, LocalDate availableDate, int quantity, LongSupplier seed) {
        if (!breaker.allow()) {
            return false;
        }
        String stockKey = StockRedisClient.stockKey(dish.getUid(), availableDate);
        boolean present;
        try {
            // Cheap probe first — only pay for the Postgres aggregate (the seed computation)
            // on the rare "first touch of this dish/date" case.
            present = redis.exists(stockKey);
        } catch (RuntimeException ex) {
            noteFailure(ex, "reserve");
            return false;
        }

        // This is the first proof Redis is alive. If it ends an outage, stale counters get
        // dropped RIGHT NOW — before the decrement below can run against one — so re-read
        // whether our key survived.
        if (noteSuccess()) {
            try {
                present = redis.exists(stockKey);
            } catch (RuntimeException ex) {
                noteFailure(ex, "reserve");
                return false;
            }
        }

        // Outside the Redis try: a missing availability row is a real error, not an outage.
        long seedValue = present ? 0 : seed.getAsLong();
        long result;
        try {
            result = redis.reserve(stockKey, quantity, seedValue);
        } catch (RuntimeException ex) {
            noteFailure(ex, "reserve");
            return false;
        }
        if (result == -1) {
            throw StockReservationException.insufficientStock(dish.getName(), availableDate);
        }
        return true;
    }

    /**
     * Django {@code _redis_credit}: best-effort give {@code quantity} back to the Redis counter
     * (only if the key exists — see {@code _CREDIT_SCRIPT}). If Redis cannot be reached the
     * counter is merely short some stock (never causes oversell) and the post-outage resync
     * fixes it.
     */
    public void credit(Dish dish, LocalDate availableDate, int quantity, long itemId) {
        if (!breaker.allow()) {
            return;
        }
        try {
            redis.credit(StockRedisClient.stockKey(dish.getUid(), availableDate), quantity);
        } catch (RuntimeException ex) {
            noteFailure(ex, "credit for item " + itemId);
            return;
        }
        noteSuccess();
    }

    /**
     * Django {@code invalidate_counter}: drop the Redis counter for one (dish, date) so the next
     * reserve() re-seeds it from Postgres. Called AFTER the commit of a chef availability edit
     * (see {@link DishInventoryService#createOrUpdateAvailability}). Best-effort: if Redis is
     * unreachable the counter is left stale, but the edit bumped {@code DishAvailability.updated_at}
     * and the failure starts an outage window, so the post-outage resync drops this key too.
     */
    public void invalidateCounter(UUID dishUid, LocalDate availableDate) {
        if (!breaker.allow()) {
            return;
        }
        try {
            redis.delete(StockRedisClient.stockKey(dishUid, availableDate));
        } catch (RuntimeException ex) {
            noteFailure(ex, "counter invalidation");
            return;
        }
        noteSuccess();
    }
}
