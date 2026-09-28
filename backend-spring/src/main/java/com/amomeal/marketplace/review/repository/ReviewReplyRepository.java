package com.amomeal.marketplace.review.repository;

import com.amomeal.marketplace.review.entity.Review;
import com.amomeal.marketplace.review.entity.ReviewReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/review/orm/review.py::ReviewORM (the reply half). */
public interface ReviewReplyRepository extends JpaRepository<ReviewReply, UUID> {

    Optional<ReviewReply> findByReview_UidAndDeletedFalse(UUID reviewUid);

    Optional<ReviewReply> findByUidAndDeletedFalse(UUID uid);

    boolean existsByReviewAndDeletedFalse(Review review);
}
