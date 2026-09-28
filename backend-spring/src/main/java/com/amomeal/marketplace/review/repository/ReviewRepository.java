package com.amomeal.marketplace.review.repository;

import com.amomeal.marketplace.review.entity.Review;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/review/orm/review.py::ReviewORM (the {@code Review} half). */
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /** Django: {@code ReviewORM.get_review_by_uid}. */
    Optional<Review> findByUidAndDeletedFalse(UUID uid);

    /** Django: {@code ReviewORM.check_existing_review}. */
    boolean existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(CustomUser owner, UUID dishUid, UUID orderUid);

    /** Django: {@code ReviewORM.get_reviews_by_dish}, rating filter + sort applied by the caller. */
    @Query("select r from Review r where r.dish.uid = :dishUid and r.deleted = false "
            + "and (:rating is null or r.rating = :rating)")
    Page<Review> findByDish(@Param("dishUid") UUID dishUid, @Param("rating") Integer rating, Pageable pageable);

    /** Django: {@code ReviewORM.get_reviews_by_user}. */
    Page<Review> findByOwnerAndDeletedFalseOrderByCreatedAtDesc(CustomUser owner, Pageable pageable);

    /** Django: {@code ReviewORM.get_dish_rating_stats} — the aggregate half. */
    @Query("select avg(r.rating), sum(r.rating * r.weight), sum(r.weight), count(r) "
            + "from Review r where r.dish.uid = :dishUid and r.deleted = false")
    List<Object[]> dishRatingAggregate(@Param("dishUid") UUID dishUid);

    /** Django: {@code ReviewORM.get_dish_rating_stats} — the rating-distribution half. */
    @Query("select r.rating, count(r) from Review r where r.dish.uid = :dishUid and r.deleted = false group by r.rating")
    List<Object[]> ratingDistribution(@Param("dishUid") UUID dishUid);

    /**
     * Django: {@code ReviewORM.get_chef_avg_rating_from_dishes} — {@code "Dish"} is the
     * global JPQL entity name for {@link com.amomeal.marketplace.dish.entity.Dish}
     * (resolved against the persistence unit, no Java import needed here).
     */
    @Query("select avg(d.avgRating) from Dish d "
            + "where d.owner.id = :chefId and d.deleted = false and exists ("
            + "  select 1 from Review r where r.dish = d and r.deleted = false)")
    Double chefAverageRatingFromDishes(@Param("chefId") Long chefId);

    /** The `review` half of `dish`'s DishStatsProvider seam — count of live reviews per dish. */
    @Query("select r.dish.uid as dishUid, count(r) as total from Review r "
            + "where r.dish.uid in :dishUids and r.deleted = false group by r.dish.uid")
    List<DishReviewCount> countByDishIn(@Param("dishUids") Collection<UUID> dishUids);

    interface DishReviewCount {
        UUID getDishUid();

        Long getTotal();
    }

    default Map<UUID, Long> reviewCountMap(Collection<UUID> dishUids) {
        if (dishUids == null || dishUids.isEmpty()) {
            return Map.of();
        }
        return countByDishIn(dishUids).stream()
                .collect(java.util.stream.Collectors.toMap(DishReviewCount::getDishUid, DishReviewCount::getTotal));
    }

    /** Django: {@code Review.objects.filter(deleted=False).aggregate(Avg("rating")) or 0.0}. */
    @Query("select avg(r.rating) from Review r where r.deleted = false")
    Double systemAverageRatingRaw();

    /** Django: {@code ReviewORM.get_chef_issue_stats} — grouped issue counts per dish for a window. */
    @Query("select r.dish.uid, r.dish.name, r.issue, count(r) from Review r "
            + "where r.dish.owner.id = :chefId and r.deleted = false and r.weight > 0 "
            + "and r.issue is not null and r.issue <> '' "
            + "and r.createdAt >= :startAt and r.createdAt < :endAt "
            + "group by r.dish.uid, r.dish.name, r.issue order by r.dish.name, r.issue")
    List<Object[]> chefIssueStats(@Param("chefId") Long chefId, @Param("startAt") Instant startAt,
                                   @Param("endAt") Instant endAt);

    /** Django: {@code AnalyticsService._base_issue_queryset} — per-review issue/date, for day-bucketed trends. */
    @Query("select r.issue, r.createdAt from Review r "
            + "where r.dish.owner.id = :chefId and r.deleted = false and r.weight > 0 "
            + "and r.issue is not null and r.issue <> '' "
            + "and r.createdAt >= :startAt and r.createdAt < :endAt")
    List<Object[]> issueDatesForChef(@Param("chefId") Long chefId, @Param("startAt") Instant startAt,
                                      @Param("endAt") Instant endAt);

    /**
     * Django: {@code ReviewORM.get_reviews_from_issue}. {@code dishUid}/{@code searchNoAccent} are
     * applied in SQL (scalar {@code is null or ...} predicates are safe); {@code menu_uid}/
     * {@code categories} (list-shaped filters) are applied by the caller in Java — see
     * {@code ReviewAnalyticsService}'s javadoc for why.
     */
    @Query("select r from Review r "
            + "where r.dish.owner.id = :chefId and r.deleted = false and r.weight > 0 "
            + "and r.issue is not null and r.issue <> '' "
            + "and r.createdAt >= :startAt and r.createdAt < :endAt "
            + "and lower(r.issue) like lower(concat('%', :issue, '%')) "
            + "and (:dishUid is null or r.dish.uid = :dishUid) "
            + "and (:searchNoAccent is null or lower(r.dish.nameNoAccent) like lower(concat('%', :searchNoAccent, '%'))) "
            + "order by r.dish.uid, r.createdAt desc")
    List<Review> findIssueReviewsForChef(@Param("chefId") Long chefId, @Param("issue") String issue,
                                          @Param("startAt") Instant startAt, @Param("endAt") Instant endAt,
                                          @Param("dishUid") UUID dishUid, @Param("searchNoAccent") String searchNoAccent);
}
