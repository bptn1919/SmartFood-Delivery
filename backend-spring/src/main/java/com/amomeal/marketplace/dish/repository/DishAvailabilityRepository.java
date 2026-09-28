package com.amomeal.marketplace.dish.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DishAvailabilityRepository extends JpaRepository<DishAvailability, Long> {

    Optional<DishAvailability> findByDishAndAvailableDate(Dish dish, LocalDate availableDate);

    /**
     * Mirrors Django's {@code select_for_update()} in DishORM.reduce_quantity /
     * increase_quantity — a row lock so concurrent inventory mutations serialize.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DishAvailability a WHERE a.dish = :dish AND a.availableDate = :availableDate")
    Optional<DishAvailability> findForUpdate(@Param("dish") Dish dish, @Param("availableDate") LocalDate availableDate);

    /** Mirrors the {@code in_stock} annotation subquery (today, is_available=True). */
    @Query("SELECT a FROM DishAvailability a WHERE a.dish.uid IN :dishUids AND a.availableDate = :date "
            + "AND a.available = true")
    List<DishAvailability> findTodayAvailability(@Param("dishUids") List<UUID> dishUids, @Param("date") LocalDate date);

    boolean existsByDishAndAvailableDateAndAvailableTrue(Dish dish, LocalDate availableDate);

    /** Mirrors DishORM.get_dish_availabilities' prefetch filter (future, available, qty &gt; 0). */
    @Query("SELECT a FROM DishAvailability a WHERE a.dish = :dish AND a.availableDate >= :from "
            + "AND a.available = true AND a.availableQuantity > 0 ORDER BY a.availableDate")
    List<DishAvailability> findUpcoming(@Param("dish") Dish dish, @Param("from") LocalDate from);

    /**
     * Post-Redis-outage resync (backend-edit 9939179, {@code DishAvailability.objects.filter(
     * updated_at__gte=since).values_list("dish_id", "available_date").distinct()}): (dish uid,
     * date) pairs whose committed quantity changed since {@code since}.
     */
    @Query("SELECT DISTINCT a.dish.uid, a.availableDate FROM DishAvailability a WHERE a.updatedAt >= :since")
    List<Object[]> findDishDatesUpdatedSince(@Param("since") java.time.Instant since);
}
