package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.config.StockProperties;
import com.amomeal.marketplace.dish.config.StockRedisClient;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.dish.exception.StockReservationException;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed fast counter + Postgres-backed durable reservation ledger for
 * Flash Sale stock holding — a faithful port of
 * ../../backend/dish/services/stock_reservation.py, including its module
 * docstring, which is reproduced below because it is the design rationale, not
 * decoration.
 *
 * <h2>Why a durable ledger ({@link StockReservation}) in addition to Redis</h2>
 * An earlier version of the Django module kept the entire "who is holding what,
 * until when" record ONLY in Redis (a hash per hold + a sorted-set index for the
 * expiry sweep). That has a hard failure mode: if Redis loses its state (crash
 * without persistence, wiped volume, failover to an empty replica), every
 * in-flight reservation simply disappears with no way to reconstruct it — orders
 * stuck PENDING forever with no sweep to cancel them, and the Redis counter
 * re-seeds from a stale Postgres value that does not know some of that stock is
 * still spoken for by unconfirmed holds.
 *
 * <p>This version keeps Redis doing ONLY the one thing it is uniquely good at:
 * an atomic, in-memory check-and-decrement counter per (dish, date), so Flash
 * Sale traffic does not serialize on a Postgres row. Everything that needs to
 * survive a Redis outage — which hold exists, its quantity, its expiry, its
 * status — lives in {@code StockReservation} (Postgres).
 *
 * <p>A consequence worth calling out: {@link #confirm} / {@link #release} do
 * their Postgres-side status transition + inventory mutation in ONE transaction
 * — atomic and idempotent by construction, because a conditional
 * {@code UPDATE ... WHERE status = 'RESERVED'} naturally affects 0 rows on a
 * retry. They only best-effort touch Redis afterwards, because Redis holds no
 * information required for correctness any more — it is a cache, not a ledger.
 *
 * <h2>Redis is NOT a hard dependency of placing an order (backend-edit 9939179)</h2>
 * {@code reserve()} talks to Redis through a small in-process circuit breaker with short
 * socket timeouts ({@link StockRedisGateway}). When Redis is unreachable (or the breaker is
 * open) it falls back to a slower-but-correct Postgres path: lock the {@code DishAvailability}
 * row with {@code SELECT ... FOR UPDATE}, compute {@code available_quantity - SUM(RESERVED)}
 * (the very same formula {@link #computeSeed} uses), and insert the ledger row in the same
 * transaction. Availability is traded against throughput: fallback requests for the same
 * dish/day serialize on the row lock.
 *
 * <p>Coming back from an outage is not free, though: every hold/release taken while Redis was
 * unreachable bypassed the counter, so a key that survived the outage (network partition,
 * failover to a replica) is STALE — the lazy seed below only self-heals keys that were lost.
 * So the first successful Redis call after any failure deletes the counters of every
 * (dish, date) whose ledger (or availability row) was touched since the failure started; the
 * next {@code reserve()} re-seeds them from Postgres.
 *
 * <h2>Data model</h2>
 * <ul>
 *   <li>Redis: {@code stock:avail:{dish_uid}:{date}} -&gt; integer counter, lazily
 *       seeded from
 *       {@code DishAvailability.available_quantity - SUM(active StockReservation)}
 *       the first time it is touched (correct even right after a Redis wipe,
 *       since it re-derives from Postgres rather than assuming a clean slate).</li>
 *   <li>Postgres: {@link StockReservation} — one row per order item, the durable
 *       hold record. See {@link #rebuildRedisCounters()} for how to force an
 *       immediate resync after a Redis incident (usually unnecessary — the lazy
 *       seed and the post-outage resync already self-heal).</li>
 * </ul>
 *
 * <p><b>PORT-NOTE (order module):</b> {@code orderItemId}/{@code orderUid} are
 * plain columns for now — see {@link StockReservation}'s class javadoc.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockReservationService {

    private final StockRedisClient redis;
    private final StockRedisGateway redisGateway;
    private final StockReservationRepository reservationRepository;
    private final DishAvailabilityRepository availabilityRepository;
    private final DishInventoryService inventoryService;
    private final StockProperties properties;

    // =====================================================================
    // Seeding
    // =====================================================================

    /**
     * Django: {@code _compute_seed}. Live availability = permanently committed
     * (Postgres) minus whatever is currently RESERVED (Postgres ledger).
     * Recomputing this from Postgres on every (re)seed is exactly what makes the
     * Redis counter correct again after Redis loses its state.
     *
     * <p>Note it deliberately does NOT filter on {@code is_available} — same as
     * Django; only the presence of the row and its quantity matter here.
     */
    @Transactional(readOnly = true)
    public long computeSeed(Dish dish, LocalDate availableDate) {
        Optional<DishAvailability> committed = availabilityRepository.findByDishAndAvailableDate(dish, availableDate);
        if (committed.isEmpty()) {
            throw StockReservationException.noAvailabilityRecord(dish.getName(), availableDate);
        }
        long held = reservationRepository.sumHeldQuantity(dish, availableDate, StockReservationStatus.RESERVED);
        return committed.get().getAvailableQuantity() - held;
    }

    // =====================================================================
    // reserve
    // =====================================================================

    /**
     * Convenience overload using the configured default TTL. {@code @Transactional} here too:
     * the call below is self-invocation (no proxy), and the Redis-down fallback's
     * {@code SELECT ... FOR UPDATE} needs a transaction (found by StockReservationRedisOutageTest).
     */
    @Transactional
    public void reserve(long itemId, UUID orderUid, Dish dish, LocalDate availableDate, int quantity) {
        reserve(itemId, orderUid, dish, availableDate, quantity, null);
    }

    /**
     * Django: {@code reserve(...)}. Atomically hold {@code quantity} units of
     * {@code dish} for {@code availableDate}.
     *
     * <p>Throws {@link StockReservationException} if the dish has no availability
     * record for that day, or not enough stock remains.
     *
     * <p>{@code order_item} is a one-to-one on {@link StockReservation}, so there
     * is at most one ledger row per item, ever. The normal caller
     * ({@code place_order}) always hits a brand-new item with no existing row — a
     * plain INSERT. The one exception is the late-webhook recovery path in
     * {@code payment/services.py::_sync_successful_payment}: if the original hold
     * already expired (TTL swept it before a delayed PayOS webhook arrived) but
     * the payment genuinely succeeded, that caller re-invokes {@code reserve()}
     * for the same item to attempt a fresh hold. In that case there is already a
     * row sitting in a terminal state (EXPIRED/RELEASED/CANCELLED) — it gets
     * reopened back to RESERVED instead of raising a uniqueness error. Reopening
     * a row that is still RESERVED/CONFIRMED (i.e. genuinely still active) is
     * refused loudly instead — that would mean {@code reserve()} was called twice
     * for the same still-live hold, which should never happen and is not safe to
     * just overwrite.
     */
    @Transactional
    public void reserve(long itemId, UUID orderUid, Dish dish, LocalDate availableDate, int quantity,
                        Long ttlSecondsOverride) {
        long ttl = ttlSecondsOverride != null ? ttlSecondsOverride : properties.getHoldTtlSeconds();
        Instant expiresAt = Instant.now().plusSeconds(ttl);

        boolean heldInRedis = redisGateway.tryHold(dish, availableDate, quantity,
                () -> computeSeed(dish, availableDate));
        if (!heldInRedis) {
            log.warn("Reserving item {} ({} x{}) through the Postgres fallback — Redis unavailable",
                    itemId, dish.getName(), quantity);
            reserveInPostgres(itemId, orderUid, dish, availableDate, quantity, expiresAt);
            return;
        }

        try {
            writeLedger(itemId, orderUid, dish, availableDate, quantity, expiresAt);
        } catch (RuntimeException ex) {
            // The durable write failed — undo the Redis-side decrement so we do not leak
            // stock, then surface the failure like any other reserve error (place_order's
            // caller rolls back siblings).
            redisGateway.credit(dish, availableDate, quantity, itemId);
            throw ex;
        }
    }

    /**
     * Django {@code _write_ledger}: {@code order_item} is one-to-one on {@link StockReservation},
     * so there is at most one ledger row per item, ever — see {@link #reserve}'s javadoc for the
     * reopen-a-terminal-row / refuse-an-active-row rule.
     */
    private void writeLedger(long itemId, UUID orderUid, Dish dish, LocalDate availableDate, int quantity,
                             Instant expiresAt) {
        StockReservation reservation = reservationRepository.findByOrderItemId(itemId).orElse(null);
        if (reservation == null) {
            reservation = StockReservation.builder()
                    .orderItemId(itemId)
                    .orderUid(orderUid)
                    .dish(dish)
                    .availableDate(availableDate)
                    .quantity(quantity)
                    .status(StockReservationStatus.RESERVED)
                    .expiresAt(expiresAt)
                    .build();
        } else {
            if (reservation.getStatus() == StockReservationStatus.RESERVED
                    || reservation.getStatus() == StockReservationStatus.CONFIRMED) {
                throw new StockReservationException("StockReservation for item " + itemId + " is already "
                        + reservation.getStatus() + " — refusing to reopen a still-active hold");
            }
            reservation.setDish(dish);
            reservation.setOrderUid(orderUid);
            reservation.setAvailableDate(availableDate);
            reservation.setQuantity(quantity);
            reservation.setStatus(StockReservationStatus.RESERVED);
            reservation.setExpiresAt(expiresAt);
        }
        // saveAndFlush, not save: Djangos ORM issues the INSERT immediately, so a constraint
        // violation must surface INSIDE the caller's try block (where the Redis decrement gets
        // compensated), not at transaction commit afterwards.
        reservationRepository.saveAndFlush(reservation);
    }

    /**
     * Django {@code _reserve_in_postgres}: the fallback hold used while Redis is unavailable —
     * the classic {@code SELECT ... FOR UPDATE}. Locking the {@code DishAvailability} row
     * serializes concurrent fallback holds (and {@code confirm()}, which locks the same row via
     * {@link DishInventoryService#reduceQuantity}), and the lock is held until this
     * transaction commits the new ledger row, so {@code committed - SUM(RESERVED) >= quantity}
     * cannot be raced past: never oversells.
     */
    private void reserveInPostgres(long itemId, UUID orderUid, Dish dish, LocalDate availableDate, int quantity,
                                   Instant expiresAt) {
        DishAvailability availability = availabilityRepository.findForUpdate(dish, availableDate)
                .orElseThrow(() -> StockReservationException.noAvailabilityRecord(dish.getName(), availableDate));
        long held = reservationRepository.sumHeldQuantity(dish, availableDate, StockReservationStatus.RESERVED);
        if (availability.getAvailableQuantity() - held < quantity) {
            throw StockReservationException.insufficientStock(dish.getName(), availableDate);
        }
        writeLedger(itemId, orderUid, dish, availableDate, quantity, expiresAt);
    }

    // =====================================================================
    // release
    // =====================================================================

    /**
     * Django: {@code release(...)}. Give back a hold. Idempotent: an explicit
     * cancel racing the expiry sweep (or being called twice) is safe — every
     * transition below is a conditional {@code UPDATE ... WHERE status =
     * <expected>}, so only the call that actually finds the row in that state
     * does anything; retries or races land on 0 rows affected and no-op.
     *
     * <p>Two distinct callers, two different meanings when the RESERVED
     * transition affects 0 rows:
     * <ul>
     *   <li>{@code expired=false} (explicit cancel, from {@code cancel_order}):
     *       the row may legitimately already be CONFIRMED — the customer/chef
     *       cancelled an order that had <i>just</i> been paid. That is a real
     *       cancellation-after-sale: flip CONFIRMED -&gt; CANCELLED and restore
     *       DishAvailability (it was really decremented, so it is really
     *       incremented back).</li>
     *   <li>{@code expired=true} (the TTL sweep): the row was RESERVED when the
     *       sweep's query selected it moments ago. If it is not RESERVED any more
     *       by the time this UPDATE runs, the ONLY way that happened is
     *       {@code confirm()} winning a race against this exact sweep tick (see
     *       ../../backend/ORDER_INVENTORY_FLOW.md section 5.5) — i.e. the payment
     *       genuinely succeeded a split second before the TTL cutoff was enforced.
     *       The sweep must back off silently here: it never legitimately intends
     *       to cancel a sale that just went through concurrently with it. Falling
     *       through to the CONFIRMED-&gt;CANCELLED branch would incorrectly refund a
     *       real, successful sale.</li>
     * </ul>
     */
    @Transactional
    public void release(long itemId, Dish dish, LocalDate availableDate, int quantity, boolean expired) {
        StockReservationStatus newStatus = expired
                ? StockReservationStatus.EXPIRED
                : StockReservationStatus.RELEASED;

        int updated = reservationRepository.transitionStatus(itemId, StockReservationStatus.RESERVED, newStatus);

        if (updated > 0) {
            // Best-effort, through the breaker; only credits an existing counter (_CREDIT_SCRIPT).
            // Redis is a cache here — worst case the counter is briefly short some stock
            // (never causes oversell); the post-outage resync / lazy reseed fixes it.
            redisGateway.credit(dish, availableDate, quantity, itemId);
            return;
        }

        if (expired) {
            // The sweep lost a race against confirm() (or another release()) for this
            // exact item — back off, nothing left to do here.
            return;
        }

        // Explicit cancel: nothing was RESERVED any more — either already terminal
        // (RELEASED/EXPIRED/CANCELLED — true no-op), or still CONFIRMED, in which case
        // the sale was final and this is a cancellation AFTER confirm: flip to
        // CANCELLED (keeps the ledger history honest) and restore Postgres. The
        // conditional UPDATE makes this branch idempotent too — a second call finds the
        // row already CANCELLED and does not double-credit DishAvailability.
        int confirmedUpdated = reservationRepository.transitionStatus(
                itemId, StockReservationStatus.CONFIRMED, StockReservationStatus.CANCELLED);
        if (confirmedUpdated > 0) {
            inventoryService.increaseQuantity(dish, availableDate, quantity);
            // backend-edit 9939179: the Redis counter was decremented at reserve() and confirm()
            // left it that way (the sale was final). Now that the sale is undone it must be
            // credited back too, otherwise Redis keeps "locking" stock that Postgres already
            // returned.
            redisGateway.credit(dish, availableDate, quantity, itemId);
        }
    }

    // =====================================================================
    // confirm
    // =====================================================================

    /**
     * Django: {@code confirm(...)}. Finalize a hold: flip the ledger row to
     * CONFIRMED and permanently deduct Postgres (source of truth) in ONE
     * transaction — atomic and idempotent by construction. A second call for the
     * same item (webhook retry, {@code sync_with_gateway}) finds no RESERVED row
     * left and safely no-ops instead of double-deducting.
     *
     * <p>Deliberately does not touch Redis: the counter was already decremented
     * at reserve() time and must stay decremented — the sale is final, Redis has
     * nothing left to do here.
     *
     * @return true if this call actually performed the RESERVED-&gt;CONFIRMED
     *         transition (and the Postgres deduction); false if there was nothing
     *         to do. <b>The caller MUST check this</b> to tell "harmless retry of
     *         an already-confirmed item" apart from "the hold was gone (expired)
     *         and nothing was ever deducted" — see
     *         {@code payment/services.py::_sync_successful_payment} for the
     *         recovery path this distinction exists for (a PayOS webhook arriving
     *         after the expiry sweep already released the hold).
     */
    @Transactional
    public boolean confirm(long itemId, Dish dish, LocalDate availableDate, int quantity) {
        int updated = reservationRepository.transitionStatus(
                itemId, StockReservationStatus.RESERVED, StockReservationStatus.CONFIRMED);
        if (updated == 0) {
            StockReservationStatus currentStatus = reservationRepository.findByOrderItemId(itemId)
                    .map(StockReservation::getStatus)
                    .orElse(null);
            log.info("Stock reservation for item {} was '{}' (not RESERVED) when confirm() was called — skipping",
                    itemId, currentStatus);
            return false;
        }
        inventoryService.reduceQuantity(dish, availableDate, quantity);
        return true;
    }

    // =====================================================================
    // expiry sweep
    // =====================================================================

    /** Snapshot of the fields the sweep needs, taken before any bulk UPDATE clears the persistence context. */
    private record ExpiredHold(long itemId, Dish dish, LocalDate availableDate, int quantity, UUID orderUid) {
    }

    /**
     * Django: {@code release_expired_holds()}. Sweep StockReservation rows whose
     * TTL passed without being confirmed/released. Returns the set of order uids
     * touched, for the caller ({@link StockHoldSweepScheduler}) to cancel those
     * orders.
     *
     * <p>Postgres-driven, not Redis-driven — this is the entire point of the
     * ledger table: it works correctly even after a Redis wipe erased every piece
     * of Redis-side bookkeeping.
     */
    @Transactional
    public Set<UUID> releaseExpiredHolds() {
        List<StockReservation> expired = reservationRepository.findExpired(
                StockReservationStatus.RESERVED, Instant.now(),
                PageRequest.of(0, properties.getSweepBatchSize()));

        // Snapshot first: release() issues a @Modifying bulk UPDATE with
        // clearAutomatically=true, which detaches everything still in the persistence
        // context — reading further rows off the original list afterwards would be
        // reading from detached entities.
        List<ExpiredHold> holds = new ArrayList<>(expired.size());
        for (StockReservation reservation : expired) {
            holds.add(new ExpiredHold(reservation.getOrderItemId(), reservation.getDish(),
                    reservation.getAvailableDate(), reservation.getQuantity(), reservation.getOrderUid()));
        }

        Set<UUID> touchedOrders = new LinkedHashSet<>();
        for (ExpiredHold hold : holds) {
            release(hold.itemId(), hold.dish(), hold.availableDate(), hold.quantity(), true);
            if (hold.orderUid() != null) {
                touchedOrders.add(hold.orderUid());
            }
        }
        return touchedOrders;
    }

    /**
     * Django: {@code rebuild_redis_counters()} (and its
     * {@code rebuild_stock_redis} management command). Force-resync every Redis
     * stock counter that currently has at least one active reservation, from
     * {@code DishAvailability - SUM(RESERVED)}. Use after a Redis incident (data
     * loss / failover to an empty instance) when you do not want to wait for the
     * lazy per-dish/date reseed on the next {@code reserve()} call.
     *
     * <p>Not required for correctness (the lazy seed already self-heals) — this is
     * an explicit operator tool for immediate, eager recovery.
     *
     * @return how many keys were (re)written
     */
    @Transactional(readOnly = true)
    public int rebuildRedisCounters() {
        List<Object[]> pairs = reservationRepository.findDistinctActiveDishDates(StockReservationStatus.RESERVED);
        int count = 0;
        for (Object[] pair : pairs) {
            Dish dish = (Dish) pair[0];
            LocalDate availableDate = (LocalDate) pair[1];
            try {
                long seed = computeSeed(dish, availableDate);
                redis.set(StockRedisClient.stockKey(dish.getUid(), availableDate), seed);
                count++;
            } catch (RuntimeException ex) {
                log.error("Failed to rebuild Redis counter for dish {}/{}: {}",
                        dish.getUid(), availableDate, ex.toString());
            }
        }
        return count;
    }
}
