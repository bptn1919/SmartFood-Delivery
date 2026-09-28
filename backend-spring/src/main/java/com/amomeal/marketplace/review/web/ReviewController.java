package com.amomeal.marketplace.review.web;

import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.review.dto.ChefIssueReportResponse;
import com.amomeal.marketplace.review.dto.CreateReviewReplyRequest;
import com.amomeal.marketplace.review.dto.CreateReviewRequest;
import com.amomeal.marketplace.review.dto.DishRatingStatsResponse;
import com.amomeal.marketplace.review.dto.ReviewDetailResponse;
import com.amomeal.marketplace.review.dto.ReviewReplyResponse;
import com.amomeal.marketplace.review.dto.ReviewResponse;
import com.amomeal.marketplace.review.dto.UpdateReviewReplyRequest;
import com.amomeal.marketplace.review.dto.UpdateReviewRequest;
import com.amomeal.marketplace.review.service.ReviewAnalyticsService;
import com.amomeal.marketplace.review.service.ReviewService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/review/api.py::ReviewController.
 *
 * <p>Django gates the whole controller with {@code auth=AuthBear()} and opts
 * exactly two GET endpoints back OUT with {@code auth=False}
 * ({@code get_reviews_by_dish}/{@code get_dish_rating_stats}) — every other
 * endpoint requires a token. Same precedent as {@code dish}'s controller
 * (see its class javadoc): the two "public" endpoints here simply don't need
 * an {@code @AuthenticationPrincipal}, but Spring Security's filter chain
 * still requires a bearer token for them (no {@code permitAll} added) —
 * revisit centrally if/when public browsing is wired project-wide.
 *
 * <p>No role gating anywhere (Django's {@code api.py} has zero
 * {@code @require_group}/{@code @require_permission} on this controller) —
 * ownership checks (own review, dish-owning chef for replies) are enforced
 * entirely in {@link ReviewService}.
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewAnalyticsService analyticsService;

    /** Django: {@code POST /api/reviews/}. */
    @PostMapping({"", "/"})
    public ReviewResponse createReview(@AuthenticationPrincipal CustomUser user, @Valid @RequestBody CreateReviewRequest payload) {
        return ReviewResponse.of(reviewService.createReview(user, payload));
    }

    /** Django: {@code GET /api/reviews/my-reviews} (paginated). */
    @GetMapping("/my-reviews")
    public PageResponse<ReviewResponse> getMyReviews(@AuthenticationPrincipal CustomUser user,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return reviewService.getReviewsByUser(user, page, pageSize);
    }

    /** Django: {@code GET /api/reviews/chef/issue-report}. */
    @GetMapping("/chef/issue-report")
    public ChefIssueReportResponse getChefIssueReport(@AuthenticationPrincipal CustomUser user) {
        return analyticsService.chefIssueReport(user);
    }

    /** Django: {@code GET /api/reviews/dish/{dish_uid}} (paginated, auth=False). */
    @GetMapping("/dish/{dishUid}")
    public PageResponse<ReviewDetailResponse> getReviewsByDish(@PathVariable UUID dishUid,
                                                                @RequestParam(required = false) Integer rating,
                                                                @RequestParam(defaultValue = "desc") String sort,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return reviewService.getReviewsByDish(dishUid, rating, sort, page, pageSize);
    }

    /** Django: {@code GET /api/reviews/dish/{dish_uid}/stats} (auth=False). */
    @GetMapping("/dish/{dishUid}/stats")
    public DishRatingStatsResponse getDishRatingStats(@PathVariable UUID dishUid) {
        return reviewService.getDishRatingStats(dishUid);
    }

    /** Django: {@code POST /api/reviews/{review_uid}/reply} — dish-owning chef only. */
    @PostMapping("/{reviewUid}/reply")
    public ReviewReplyResponse createReviewReply(@AuthenticationPrincipal CustomUser user, @PathVariable UUID reviewUid,
                                                  @Valid @RequestBody CreateReviewReplyRequest payload) {
        return ReviewReplyResponse.of(reviewService.createReviewReply(reviewUid, user, payload));
    }

    /** Django: {@code GET /api/reviews/{review_uid}/reply}. */
    @GetMapping("/{reviewUid}/reply")
    public ReviewReplyResponse getReviewReply(@PathVariable UUID reviewUid) {
        return ReviewReplyResponse.of(reviewService.getReviewReplyByReviewUid(reviewUid));
    }

    /** Django: {@code PATCH /api/reviews/reply/{reply_uid}}. */
    @PatchMapping("/reply/{replyUid}")
    public ReviewReplyResponse updateReviewReply(@AuthenticationPrincipal CustomUser user, @PathVariable UUID replyUid,
                                                  @Valid @RequestBody UpdateReviewReplyRequest payload) {
        return ReviewReplyResponse.of(reviewService.updateReviewReply(replyUid, user, payload));
    }

    /** Django: {@code DELETE /api/reviews/reply/{reply_uid}}. */
    @DeleteMapping("/reply/{replyUid}")
    public Map<String, Object> deleteReviewReply(@AuthenticationPrincipal CustomUser user, @PathVariable UUID replyUid) {
        reviewService.deleteReviewReply(replyUid, user);
        return Map.of("success", true, "message", "Reply đã được xóa thành công");
    }

    /** Django: {@code GET /api/reviews/{uid}}. */
    @GetMapping("/{uid}")
    public ReviewResponse getReviewByUid(@PathVariable UUID uid) {
        return ReviewResponse.of(reviewService.getReviewByUid(uid));
    }

    /** Django: {@code PATCH /api/reviews/{uid}} — owner only. */
    @PatchMapping("/{uid}")
    public ReviewResponse updateReview(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                        @RequestBody UpdateReviewRequest payload) {
        return ReviewResponse.of(reviewService.updateReview(uid, user, payload));
    }

    /** Django: {@code DELETE /api/reviews/{uid}} — owner only, soft delete. */
    @DeleteMapping("/{uid}")
    public Map<String, Object> deleteReview(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        reviewService.deleteReview(uid, user);
        return Map.of("success", true, "message", "Review đã được xóa thành công");
    }
}
