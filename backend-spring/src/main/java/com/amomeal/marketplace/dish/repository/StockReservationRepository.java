package com.amomeal.marketplace.dish.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    Optional<StockReservation> findByOrderItemId(Long orderItemId);

    /**
     * Mirrors Django's conditional
     * {@code StockReservation.objects.filter(order_item_id=..., status=<expected>).update(status=<new>)}.
     * The whole idempotency story of confirm()/release() rests on this returning
     * 0 rows when the row is not in the expected state — a retry or a lost race
     * lands on 0 and no-ops instead of double-applying an inventory mutation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockReservation r SET r.status = :newStatus, r.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE r.orderItemId = :orderItemId AND r.status = :expectedStatus")
    int transitionStatus(@Param("orderItemId") Long orderItemId,
                         @Param("expectedStatus") StockReservationStatus expectedStatus,
                         @Param("newStatus") StockReservationStatus newStatus);

    /** Mirrors the {@code Sum("quantity")} aggregate in StockReservationService._compute_seed. */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM StockReservation r "
            + "WHERE r.dish = :dish AND r.availableDate = :availableDate AND r.status = :status")
    long sumHeldQuantity(@Param("dish") Dish dish, @Param("availableDate") LocalDate availableDate,
                         @Param("status") StockReservationStatus status);

    /** Mirrors the expiry sweep query (RESERVED and expires_at &lt;= now, capped at 500). */
    @Query("SELECT r FROM StockReservation r JOIN FETCH r.dish "
            + "WHERE r.status = :status AND r.expiresAt <= :now ORDER BY r.expiresAt")
    List<StockReservation> findExpired(@Param("status") StockReservationStatus status,
                                       @Param("now") Instant now, Pageable pageable);

    /** Mirrors rebuild_redis_counters' distinct (dish, date) scan over active holds. */
    @Query("SELECT DISTINCT r.dish, r.availableDate FROM StockReservation r WHERE r.status = :status")
    List<Object[]> findDistinctActiveDishDates(@Param("status") StockReservationStatus status);

    /** Post-Redis-outage resync: (dish uid, date) pairs whose ledger rows moved since {@code since}. */
    @Query("SELECT DISTINCT r.dish.uid, r.availableDate FROM StockReservation r WHERE r.updatedAt >= :since")
    List<Object[]> findDishDatesUpdatedSince(@Param("since") Instant since);
}
