package com.amomeal.marketplace.recommendation.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * recommendation-owned read repository over {@code dish}'s {@link Dish} entity (same pattern as
 * {@code order}'s {@code OrderAppliedVoucherRepository} over voucher's entity) — every query
 * fetch-joins the attachment, mirroring Django's {@code select_related("attachment")}, so the
 * pipeline can run outside a transaction like Django's autocommit code.
 */
public interface RecommendationDishRepository extends JpaRepository<Dish, UUID> {

    @Query("select distinct d from Dish d left join fetch d.attachment where d.deleted = false and d.uid in :uids")
    List<Dish> findActiveWithAttachment(@Param("uids") Collection<UUID> uids);

    @Query("select d from Dish d left join fetch d.attachment where d.uid in :uids")
    List<Dish> findAllWithAttachment(@Param("uids") Collection<UUID> uids);

    @Query("select d from Dish d left join fetch d.attachment where d.uid = :uid and d.deleted = false")
    Optional<Dish> findActive(@Param("uid") UUID uid);

    /** Django: {@code Dish.objects.filter(deleted=False, avg_rating__gte=4.0).order_by("-avg_rating")[:n]}. */
    @Query("select d from Dish d left join fetch d.attachment where d.deleted = false and d.avgRating >= 4.0 "
            + "order by d.avgRating desc")
    List<Dish> findPopularFallback(Pageable pageable);

    /** Django balanced fallback: {@code filter(deleted=False, status="AVAILABLE").order_by("-avg_rating", "-final_score")}. */
    @Query("select d from Dish d left join fetch d.attachment where d.deleted = false and d.status = :status "
            + "order by d.avgRating desc, d.finalScore desc")
    List<Dish> findByStatusRanked(@Param("status") DishStatus status, Pageable pageable);
}
