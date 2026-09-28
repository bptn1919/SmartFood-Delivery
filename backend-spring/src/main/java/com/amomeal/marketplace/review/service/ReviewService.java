package com.amomeal.marketplace.review.service;

import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.exception.DishNotFoundInOrderException;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.exception.OrderNotFoundException;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.review.dto.CreateReviewReplyRequest;
import com.amomeal.marketplace.review.dto.CreateReviewRequest;
import com.amomeal.marketplace.review.dto.DishRatingStatsResponse;
import com.amomeal.marketplace.review.dto.ReviewDetailResponse;
import com.amomeal.marketplace.review.dto.ReviewResponse;
import com.amomeal.marketplace.review.dto.UpdateReviewReplyRequest;
import com.amomeal.marketplace.review.dto.UpdateReviewRequest;
import com.amomeal.marketplace.review.entity.Review;
import com.amomeal.marketplace.review.entity.ReviewReply;
import com.amomeal.marketplace.review.exception.DuplicateReviewException;
import com.amomeal.marketplace.review.exception.InvalidRatingException;
import com.amomeal.marketplace.review.exception.NotDishOwnerException;
import com.amomeal.marketplace.review.exception.OrderNotCompletedException;
import com.amomeal.marketplace.review.exception.PermissionDeniedException;
import com.amomeal.marketplace.review.exception.ReviewNotFoundException;
import com.amomeal.marketplace.review.exception.ReviewReplyAlreadyExistsException;
import com.amomeal.marketplace.review.exception.ReviewReplyNotFoundException;
import com.amomeal.marketplace.review.repository.ReviewReplyRepository;
import com.amomeal.marketplace.review.repository.ReviewRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1:1 port of ../../backend/review/services/__init__.py::ReviewService (the
 * review/reply half — analytics lives in {@link ReviewAnalyticsService}).
 *
 * <p>Reuses {@code dish}/{@code order}/{@code profile}/{@code attachment}/
 * {@code users} entities and repositories as-is (CLAUDE.md §8 task
 * instructions) — no new entity/repository is declared for any of them here.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewReplyRepository reviewReplyRepository;
    private final DishRepository dishRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ChefProfileRepository chefProfileRepository;
    private final AttachmentService attachmentService;
    private final AiModelClient aiModelClient;

    // =====================================================================
    // Review CRUD
    // =====================================================================

    /**
     * Django: {@code ReviewService.create_review}. Business rules, in Django's
     * exact check order: rating range -> dish exists -> order exists -> order
     * COMPLETED -> dish actually in that order -> not already reviewed.
     */
    @Transactional
    public Review createReview(CustomUser user, CreateReviewRequest payload) {
        int rating = payload.rating() == null ? 0 : payload.rating();
        if (rating < 1 || rating > 5) {
            throw new InvalidRatingException();
        }

        Dish dish = dishRepository.findByUidAndDeletedFalse(payload.dishUid())
                .orElseThrow(DishNotFoundException::new);

        Order order = orderRepository.findById(payload.orderUid())
                .orElseThrow(OrderNotFoundException::new);

        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new OrderNotCompletedException();
        }

        // Django: order_service.check_dish_in_order raises exceptions.dishes.
        // DishNotFoundInOrderException (NOT review's own DishNotOrderedException,
        // which is dead code here — see that class's javadoc).
        if (!orderItemRepository.existsByOrderUidAndDishUid(order.getUid(), dish.getUid())) {
            throw new DishNotFoundInOrderException();
        }

        if (reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(user, dish.getUid(), order.getUid())) {
            throw new DuplicateReviewException();
        }

        // PORT-NOTE (real, reachable Django bug — preserved verbatim, see PROGRESS.md):
        // handle_attachment(uid) is called ONLY for its validation side effect
        // (raises AttachmentNotFoundException/AttachmentIsNotCompletedException for a
        // bad attachment_uid); Django's `review/services/__init__.py::create_review`
        // NEVER assigns its return value to the `attachment` variable actually passed
        // to ReviewORM.create_review — `attachment` stays None regardless. A review
        // created with a perfectly valid attachment_uid therefore NEVER actually links
        // the attachment: attachment_url in every response is always null. Not fixed —
        // this is a customer-facing but non-critical (photo-optional) feature, and
        // FE-admin does not consume any `review` endpoint at all (grepped — zero
        // matches), so there is no known consumer relying on either behavior.
        if (payload.attachmentUid() != null) {
            attachmentService.handleAttachment(payload.attachmentUid());
        }

        Instant now = Instant.now();
        AiPrediction prediction = predictReviewLabel(rating, payload.comment(), user.getId(), dish.getUid(),
                order.getUid(), null, now, now, payload.attachmentUid(), false);

        Review review = Review.builder()
                .rating(rating)
                .comment(payload.comment())
                .weight(prediction.weight())
                .issue(prediction.issue())
                .dish(dish)
                .order(order)
                .owner(user)
                .attachment(null)
                .build();
        review = reviewRepository.save(review);

        updateDishAvgRating(dish.getUid());
        if (dish.getOwner() != null) {
            updateChefAvgRating(dish.getOwner().getId());
        }
        return review;
    }

    @Transactional(readOnly = true)
    public Review getReviewByUid(UUID uid) {
        return reviewRepository.findByUidAndDeletedFalse(uid).orElseThrow(ReviewNotFoundException::new);
    }

    /** Django: {@code get_reviews_by_dish} (+ the {@code @paginate} decorator), response=ReviewDetailResponse. */
    @Transactional(readOnly = true)
    public PageResponse<ReviewDetailResponse> getReviewsByDish(UUID dishUid, Integer rating, String sort, int page, int pageSize) {
        dishRepository.findByUidAndDeletedFalse(dishUid).orElseThrow(DishNotFoundException::new);
        Sort.Direction direction = "asc".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), pageSize, Sort.by(direction, "createdAt"));
        var result = reviewRepository.findByDish(dishUid, rating, pageable);
        List<ReviewDetailResponse> content = result.getContent().stream().map(ReviewDetailResponse::of).toList();
        return PageResponse.of(content, page, pageSize, result.getTotalElements());
    }

    /** Django: {@code get_reviews_by_user} (+ the {@code @paginate} decorator on {@code /my-reviews}), response=ReviewResponse. */
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> getReviewsByUser(CustomUser user, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), pageSize);
        var result = reviewRepository.findByOwnerAndDeletedFalseOrderByCreatedAtDesc(user, pageable);
        List<ReviewResponse> content = result.getContent().stream().map(ReviewResponse::of).toList();
        return PageResponse.of(content, page, pageSize, result.getTotalElements());
    }

    /** Django: {@code ReviewService.update_review} — owner-only. */
    @Transactional
    public Review updateReview(UUID uid, CustomUser user, UpdateReviewRequest payload) {
        Review review = getReviewByUid(uid);
        if (!isOwner(review, user)) {
            throw new PermissionDeniedException("Bạn chỉ có thể update review của mình");
        }
        if (payload.rating() != null && (payload.rating() < 1 || payload.rating() > 5)) {
            throw new InvalidRatingException();
        }

        // PORT-NOTE: same discarded-return-value bug as createReview — see its javadoc.
        if (payload.attachmentUid() != null) {
            attachmentService.handleAttachment(payload.attachmentUid());
        }

        int currentRating = payload.rating() != null ? payload.rating() : review.getRating();
        String currentComment = payload.comment() != null ? payload.comment() : review.getComment();
        UUID attachmentUidForAi = payload.attachmentUid() != null
                ? payload.attachmentUid()
                : (review.getAttachment() != null ? review.getAttachment().getUid() : null);

        AiPrediction prediction = predictReviewLabel(currentRating, currentComment, user.getId(),
                review.getDish().getUid(), review.getOrder() != null ? review.getOrder().getUid() : null,
                review.getUid(), review.getCreatedAt(), Instant.now(), attachmentUidForAi, review.isDeleted());

        if (payload.rating() != null) {
            review.setRating(payload.rating());
        }
        if (payload.comment() != null) {
            review.setComment(payload.comment());
        }
        // Django never re-assigns `review.attachment` here either (same discarded-value
        // bug — the local `attachment` variable it would assign from is always None).
        review.setWeight(prediction.weight());
        review.setIssue(prediction.issue());
        review = reviewRepository.save(review);

        // Django always recomputes the weighted score (weight may have changed even if
        // rating didn't, because the comment did).
        updateDishAvgRating(review.getDish().getUid());
        if (review.getDish().getOwner() != null) {
            updateChefAvgRating(review.getDish().getOwner().getId());
        }
        return review;
    }

    /** Django: {@code ReviewService.delete_review} — owner-only, soft delete. */
    @Transactional
    public void deleteReview(UUID uid, CustomUser user) {
        Review review = getReviewByUid(uid);
        if (!isOwner(review, user)) {
            throw new PermissionDeniedException("Bạn chỉ có thể xóa review của mình");
        }
        review.setDeleted(true);
        reviewRepository.save(review);

        updateDishAvgRating(review.getDish().getUid());
        if (review.getDish().getOwner() != null) {
            updateChefAvgRating(review.getDish().getOwner().getId());
        }
    }

    /** Django: {@code ReviewService.get_dish_rating_stats} — PUBLIC. */
    @Transactional(readOnly = true)
    public DishRatingStatsResponse getDishRatingStats(UUID dishUid) {
        dishRepository.findByUidAndDeletedFalse(dishUid).orElseThrow(DishNotFoundException::new);

        List<Object[]> agg = reviewRepository.dishRatingAggregate(dishUid);
        Object[] row = agg.isEmpty() ? new Object[]{null, null, null, 0L} : agg.get(0);
        double avgRating = row[0] == null ? 0.0 : round2((Double) row[0]);
        long totalReviews = row[3] == null ? 0L : (Long) row[3];

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            distribution.put(i, 0L);
        }
        for (Object[] r : reviewRepository.ratingDistribution(dishUid)) {
            distribution.put((Integer) r[0], (Long) r[1]);
        }
        return new DishRatingStatsResponse(avgRating, totalReviews, distribution);
    }

    // =====================================================================
    // Review Reply
    // =====================================================================

    /** Django: {@code create_review_reply} — chef (dish owner) only, at most one reply per review. */
    @Transactional
    public ReviewReply createReviewReply(UUID reviewUid, CustomUser user, CreateReviewReplyRequest payload) {
        Review review = getReviewByUid(reviewUid);
        if (!checkDishOwnership(review.getDish(), user)) {
            throw new NotDishOwnerException();
        }
        if (reviewReplyRepository.existsByReviewAndDeletedFalse(review)) {
            throw new ReviewReplyAlreadyExistsException();
        }
        ReviewReply reply = ReviewReply.builder()
                .review(review)
                .owner(user)
                .content(payload.content())
                .build();
        return reviewReplyRepository.save(reply);
    }

    @Transactional(readOnly = true)
    public ReviewReply getReviewReplyByReviewUid(UUID reviewUid) {
        return reviewReplyRepository.findByReview_UidAndDeletedFalse(reviewUid)
                .orElseThrow(ReviewReplyNotFoundException::new);
    }

    /** Django: {@code update_review_reply} — same ownership rule, but a plain PermissionDeniedError (not NotDishOwnerException). */
    @Transactional
    public ReviewReply updateReviewReply(UUID replyUid, CustomUser user, UpdateReviewReplyRequest payload) {
        ReviewReply reply = reviewReplyRepository.findByUidAndDeletedFalse(replyUid)
                .orElseThrow(ReviewReplyNotFoundException::new);
        if (!checkDishOwnership(reply.getReview().getDish(), user)) {
            throw new PermissionDeniedException("Bạn chỉ có thể update reply của mình");
        }
        reply.setContent(payload.content());
        return reviewReplyRepository.save(reply);
    }

    /** Django: {@code delete_review_reply} — soft delete, same ownership rule as update. */
    @Transactional
    public void deleteReviewReply(UUID replyUid, CustomUser user) {
        ReviewReply reply = reviewReplyRepository.findByUidAndDeletedFalse(replyUid)
                .orElseThrow(ReviewReplyNotFoundException::new);
        if (!checkDishOwnership(reply.getReview().getDish(), user)) {
            throw new PermissionDeniedException("Bạn chỉ có thể xóa reply của mình");
        }
        reply.setDeleted(true);
        reviewReplyRepository.save(reply);
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private boolean isOwner(Review review, CustomUser user) {
        return review.getOwner() != null && review.getOwner().getId().equals(user.getId());
    }

    private boolean checkDishOwnership(Dish dish, CustomUser user) {
        return dish.getOwner() != null && dish.getOwner().getId().equals(user.getId());
    }

    /** Django: {@code ReviewService._update_dish_avg_rating}. */
    void updateDishAvgRating(UUID dishUid) {
        List<Object[]> agg = reviewRepository.dishRatingAggregate(dishUid);
        Object[] row = agg.isEmpty() ? new Object[]{null, null, null, 0L} : agg.get(0);
        Double avgRating = (Double) row[0];
        Double weightedSum = (Double) row[1];
        Double totalWeight = (Double) row[2];

        double avg = avgRating == null ? 0.0 : round2(avgRating);
        double finalScore = (totalWeight != null && totalWeight > 0) ? round2(weightedSum / totalWeight) : 0.0;

        dishRepository.findByUid(dishUid).ifPresent(dish -> {
            dish.setAvgRating(avg);
            dish.setFinalScore(finalScore);
            dishRepository.save(dish);
        });
    }

    /** Django: {@code ReviewService._update_chef_avg_rating}. */
    void updateChefAvgRating(Long chefId) {
        Double avg = reviewRepository.chefAverageRatingFromDishes(chefId);
        double rating = avg == null ? 0.0 : avg;
        chefProfileRepository.findByUserId(chefId).ifPresent(profile -> {
            profile.setRating(rating);
            chefProfileRepository.save(profile);
        });
    }

    /**
     * Django: {@code ReviewService._predict_review_label}. Comment-empty short
     * circuit happens BEFORE the AI call is even attempted (Django: {@code if not
     * comment: return 0.0, None}) — everything past that point can never throw
     * (see {@link AiModelClient}'s javadoc).
     */
    AiPrediction predictReviewLabel(int rating, String comment, Long ownerId, UUID dishUid, UUID orderUid,
                                     UUID reviewUid, Instant createdAt, Instant updatedAt, UUID attachmentUid,
                                     boolean deleted) {
        if (comment == null || comment.isEmpty()) {
            return AiPrediction.FALLBACK;
        }
        return aiModelClient.predict(new AiPredictionRequest(reviewUid, createdAt, updatedAt, rating, comment,
                deleted, attachmentUid, dishUid, orderUid, ownerId));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
