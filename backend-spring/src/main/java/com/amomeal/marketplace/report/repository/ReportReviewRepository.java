package com.amomeal.marketplace.report.repository;

import com.amomeal.marketplace.review.entity.Review;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * report-owned read-only view over review's entity: Django's _compute_review_signal / delivery review query -
 * reviews with an AI issue in a label set, grouped by order with the max weight (a NULL order forms one group,
 * like Django's values("order_id")). Rows are [orderUid, maxWeight].
 */
public interface ReportReviewRepository extends Repository<Review, UUID> {

    @Query("select r.order.uid, max(r.weight) from Review r where r.dish.owner.id = :chefId and r.deleted = false "
            + "and r.weight > 0 and r.issue in :issues and r.createdAt >= :cutoff group by r.order.uid")
    List<Object[]> maxWeightByOrder(@Param("chefId") Long chefId, @Param("issues") Collection<String> issues,
                                    @Param("cutoff") Instant cutoff);

    @Query("select r.order.uid, max(r.weight) from Review r where r.dish.owner.id = :chefId and r.deleted = false "
            + "and r.weight > 0 and r.issue in :issues and r.createdAt >= :cutoff and r.dish.uid = :dishUid "
            + "group by r.order.uid")
    List<Object[]> maxWeightByOrderForDish(@Param("chefId") Long chefId, @Param("issues") Collection<String> issues,
                                           @Param("cutoff") Instant cutoff, @Param("dishUid") UUID dishUid);
}
