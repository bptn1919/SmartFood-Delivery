package com.amomeal.marketplace.recommendation.repository;

import com.amomeal.marketplace.recommendation.entity.DailyMealLog;
import com.amomeal.marketplace.recommendation.entity.MealSource;
import com.amomeal.marketplace.recommendation.entity.UserDailyNutrition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyMealLogRepository extends JpaRepository<DailyMealLog, UUID> {

    /** Django: {@code DailyMealLog.objects.filter(daily_nutrition=daily, is_deleted=False)}. */
    List<DailyMealLog> findAllByDailyNutritionAndDeletedFalse(UserDailyNutrition dailyNutrition);

    /** Django: {@code _get_active_meal_logs} — {@code .order_by("created_at", "updated_at")} + select_related dish/attachment. */
    @Query("select l from DailyMealLog l left join fetch l.dish d left join fetch d.attachment "
            + "where l.dailyNutrition = :daily and l.deleted = false order by l.createdAt, l.updatedAt")
    List<DailyMealLog> findActiveOrdered(@Param("daily") UserDailyNutrition daily);

    /** First dedupe lookup of {@code _sync_app_order_logs}: (daily, source=APP, source_ref). */
    Optional<DailyMealLog> findFirstByDailyNutritionAndSourceAndSourceRefOrderByUidAsc(
            UserDailyNutrition daily, MealSource source, String sourceRef);

    /** Second dedupe lookup: {@code raw_payload__order_item_id=str(row.id)} (JSON key equality). */
    @Query(value = "SELECT * FROM daily_meal_log WHERE daily_nutrition_uid = :daily AND source = 'APP' "
            + "AND raw_payload -> 'order_item_id' = to_jsonb(CAST(:orderItemId AS text)) ORDER BY uid LIMIT 1",
            nativeQuery = true)
    Optional<DailyMealLog> findAppLogByOrderItemId(@Param("daily") UUID dailyUid, @Param("orderItemId") String orderItemId);

    /** Third dedupe lookup: {@code raw_payload__order_uid=..., dish_id=..., quantity_multiplier=...}. */
    @Query(value = "SELECT * FROM daily_meal_log WHERE daily_nutrition_uid = :daily AND source = 'APP' "
            + "AND raw_payload -> 'order_uid' = to_jsonb(CAST(:orderUid AS text)) AND dish_uid = :dishUid "
            + "AND quantity_multiplier = :quantity ORDER BY uid LIMIT 1",
            nativeQuery = true)
    Optional<DailyMealLog> findAppLogByOrderUidDishQuantity(@Param("daily") UUID dailyUid, @Param("orderUid") String orderUid,
                                                            @Param("dishUid") UUID dishUid, @Param("quantity") double quantity);

    /** Django: {@code DailyMealLog.objects.select_related(...).filter(uid=..., daily_nutrition__user_id=..., is_deleted=False).first()}. */
    @Query("select l from DailyMealLog l join fetch l.dailyNutrition dn left join fetch l.dish "
            + "where l.uid = :uid and dn.userId = :userId and l.deleted = false")
    Optional<DailyMealLog> findActiveForUser(@Param("uid") UUID uid, @Param("userId") Long userId);
}
