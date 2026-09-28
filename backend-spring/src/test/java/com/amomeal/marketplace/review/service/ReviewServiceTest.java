package com.amomeal.marketplace.review.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.exception.DishNotFoundInOrderException;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.exception.OrderNotFoundException;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.review.dto.CreateReviewReplyRequest;
import com.amomeal.marketplace.review.dto.CreateReviewRequest;
import com.amomeal.marketplace.review.dto.UpdateReviewReplyRequest;
import com.amomeal.marketplace.review.entity.Review;
import com.amomeal.marketplace.review.entity.ReviewReply;
import com.amomeal.marketplace.review.exception.DuplicateReviewException;
import com.amomeal.marketplace.review.exception.InvalidRatingException;
import com.amomeal.marketplace.review.exception.NotDishOwnerException;
import com.amomeal.marketplace.review.exception.OrderNotCompletedException;
import com.amomeal.marketplace.review.exception.PermissionDeniedException;
import com.amomeal.marketplace.review.exception.ReviewReplyAlreadyExistsException;
import com.amomeal.marketplace.review.repository.ReviewReplyRepository;
import com.amomeal.marketplace.review.repository.ReviewRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock ReviewReplyRepository reviewReplyRepository;
    @Mock DishRepository dishRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock ChefProfileRepository chefProfileRepository;
    @Mock AttachmentService attachmentService;
    @Mock AiModelClient aiModelClient;

    ReviewService service;

    CustomUser customer;
    CustomUser chef;
    Dish dish;
    Order order;
    UUID dishUid;
    UUID orderUid;

    @BeforeEach
    void setUp() {
        service = new ReviewService(reviewRepository, reviewReplyRepository, dishRepository, orderRepository,
                orderItemRepository, chefProfileRepository, attachmentService, aiModelClient);

        customer = new CustomUser();
        customer.setId(1L);
        chef = new CustomUser();
        chef.setId(2L);

        dishUid = UUID.randomUUID();
        dish = Dish.builder().uid(dishUid).name("Pho").price(new BigDecimal("50000")).owner(chef).build();

        orderUid = UUID.randomUUID();
        order = Order.builder().uid(orderUid).status(OrderStatus.COMPLETED).build();

        when(dishRepository.findByUidAndDeletedFalse(dishUid)).thenReturn(Optional.of(dish));
        when(dishRepository.findByUid(dishUid)).thenReturn(Optional.of(dish));
        when(orderRepository.findById(orderUid)).thenReturn(Optional.of(order));
        when(orderItemRepository.existsByOrderUidAndDishUid(orderUid, dishUid)).thenReturn(true);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.dishRatingAggregate(any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{4.5, 4.5, 1.0, 1L}));
        when(chefProfileRepository.findByUserId(any())).thenReturn(Optional.of(ChefProfile.builder().user(chef).build()));
        when(reviewRepository.chefAverageRatingFromDishes(any())).thenReturn(4.5);
        when(aiModelClient.predict(any())).thenReturn(AiPrediction.FALLBACK);
    }

    // =====================================================================
    // create_review — validation order
    // =====================================================================

    @Test
    void createReview_ratingBelow1_throwsInvalidRating() {
        CreateReviewRequest payload = new CreateReviewRequest(dishUid, orderUid, 0, "great", null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(InvalidRatingException.class);
        verifyNoInteractions(dishRepository);
    }

    @Test
    void createReview_ratingAbove5_throwsInvalidRating() {
        CreateReviewRequest payload = new CreateReviewRequest(dishUid, orderUid, 6, "great", null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(InvalidRatingException.class);
    }

    @Test
    void createReview_rating1and5_areValid() {
        when(reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(any(), any(), any())).thenReturn(false);

        Review low = service.createReview(customer, new CreateReviewRequest(dishUid, orderUid, 1, null, null));
        Review high = service.createReview(customer, new CreateReviewRequest(dishUid, orderUid, 5, null, null));

        assertThat(low.getRating()).isEqualTo(1);
        assertThat(high.getRating()).isEqualTo(5);
    }

    @Test
    void createReview_dishNotFound_throwsDishNotFoundException() {
        UUID missingDish = UUID.randomUUID();
        when(dishRepository.findByUidAndDeletedFalse(missingDish)).thenReturn(Optional.empty());
        CreateReviewRequest payload = new CreateReviewRequest(missingDish, orderUid, 5, null, null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(DishNotFoundException.class);
    }

    @Test
    void createReview_orderNotFound_throwsOrderNotFoundException() {
        UUID missingOrder = UUID.randomUUID();
        when(orderRepository.findById(missingOrder)).thenReturn(Optional.empty());
        CreateReviewRequest payload = new CreateReviewRequest(dishUid, missingOrder, 5, null, null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void createReview_orderNotCompleted_throwsOrderNotCompletedException() {
        order.setStatus(OrderStatus.PENDING);
        CreateReviewRequest payload = new CreateReviewRequest(dishUid, orderUid, 5, null, null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(OrderNotCompletedException.class);
    }

    @Test
    void createReview_dishNotInOrder_throwsDishNotFoundInOrderException() {
        when(orderItemRepository.existsByOrderUidAndDishUid(orderUid, dishUid)).thenReturn(false);
        CreateReviewRequest payload = new CreateReviewRequest(dishUid, orderUid, 5, null, null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(DishNotFoundInOrderException.class);
    }

    @Test
    void createReview_duplicate_throwsDuplicateReviewException() {
        when(reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(customer, dishUid, orderUid)).thenReturn(true);
        CreateReviewRequest payload = new CreateReviewRequest(dishUid, orderUid, 5, null, null);
        assertThatThrownBy(() -> service.createReview(customer, payload)).isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    void createReview_noComment_skipsAiCallEntirely() {
        when(reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(any(), any(), any())).thenReturn(false);
        Review review = service.createReview(customer, new CreateReviewRequest(dishUid, orderUid, 5, null, null));
        verifyNoInteractions(aiModelClient);
        assertThat(review.getWeight()).isEqualTo(0.0);
        assertThat(review.getIssue()).isNull();
    }

    @Test
    void createReview_withComment_usesAiPredictionAndAlwaysSaves() {
        when(reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(any(), any(), any())).thenReturn(false);
        when(aiModelClient.predict(any())).thenReturn(new AiPrediction(0.8, "SPICY"));

        Review review = service.createReview(customer, new CreateReviewRequest(dishUid, orderUid, 5, "too spicy", null));

        assertThat(review.getWeight()).isEqualTo(0.8);
        assertThat(review.getIssue()).isEqualTo("SPICY");
        verify(dishRepository).save(any(Dish.class));
        verify(chefProfileRepository).save(any(ChefProfile.class));
    }

    /**
     * Task requirement: "does the review still save, or does the request fail?" — Django's
     * _predict_review_label NEVER raises AIModelUnavailableException/AIModelInvalidResponseException
     * (dead code — see AiModelClient's javadoc); every AI failure falls back to (0.0, null) and
     * the review write proceeds. Simulated here by having the (mocked) AiModelClient return the
     * fallback value itself, exactly what RestClientAiModelClient does internally on a real failure
     * (see RestClientAiModelClientTest for that half).
     */
    @Test
    void createReview_aiModelUnavailable_reviewStillSavesWithFallbackWeight() {
        when(reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(any(), any(), any())).thenReturn(false);
        when(aiModelClient.predict(any())).thenReturn(AiPrediction.FALLBACK);

        Review review = service.createReview(customer, new CreateReviewRequest(dishUid, orderUid, 5, "comment", null));

        assertThat(review).isNotNull();
        assertThat(review.getWeight()).isEqualTo(0.0);
        assertThat(review.getIssue()).isNull();
    }

    @Test
    void createReview_withAttachmentUid_validatesButNeverLinksAttachment() {
        // PORT-NOTE regression test: Django's create_review calls handle_attachment(uid) for its
        // validation side effect only, then discards the return value -- the review is always
        // created with attachment=null regardless of a valid attachment_uid. See ReviewService's
        // javadoc.
        when(reviewRepository.existsByOwnerAndDish_UidAndOrder_UidAndDeletedFalse(any(), any(), any())).thenReturn(false);
        UUID attachmentUid = UUID.randomUUID();
        when(attachmentService.handleAttachment(attachmentUid)).thenReturn(mock(Attachment.class));

        Review review = service.createReview(customer, new CreateReviewRequest(dishUid, orderUid, 5, null, attachmentUid));

        verify(attachmentService).handleAttachment(attachmentUid);
        assertThat(review.getAttachment()).isNull();
    }

    // =====================================================================
    // update / delete review — ownership
    // =====================================================================

    @Test
    void updateReview_notOwner_throwsPermissionDenied() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(chef).rating(3).build();
        when(reviewRepository.findByUidAndDeletedFalse(review.getUid())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.updateReview(review.getUid(), customer, new com.amomeal.marketplace.review.dto.UpdateReviewRequest(4, null, null)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void updateReview_invalidRating_throwsInvalidRatingException() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(customer).rating(3).build();
        when(reviewRepository.findByUidAndDeletedFalse(review.getUid())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.updateReview(review.getUid(), customer, new com.amomeal.marketplace.review.dto.UpdateReviewRequest(0, null, null)))
                .isInstanceOf(InvalidRatingException.class);
    }

    @Test
    void deleteReview_notOwner_throwsPermissionDenied() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(chef).rating(3).build();
        when(reviewRepository.findByUidAndDeletedFalse(review.getUid())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.deleteReview(review.getUid(), customer)).isInstanceOf(PermissionDeniedException.class);
    }

    // =====================================================================
    // Reply — owner-only rules
    // =====================================================================

    @Test
    void createReviewReply_notDishOwner_throwsNotDishOwnerException() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(customer).rating(5).build();
        when(reviewRepository.findByUidAndDeletedFalse(review.getUid())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.createReviewReply(review.getUid(), customer, new CreateReviewReplyRequest("thanks")))
                .isInstanceOf(NotDishOwnerException.class);
    }

    @Test
    void createReviewReply_byDishOwner_succeeds() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(customer).rating(5).build();
        when(reviewRepository.findByUidAndDeletedFalse(review.getUid())).thenReturn(Optional.of(review));
        when(reviewReplyRepository.existsByReviewAndDeletedFalse(review)).thenReturn(false);
        when(reviewReplyRepository.save(any(ReviewReply.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewReply reply = service.createReviewReply(review.getUid(), chef, new CreateReviewReplyRequest("thanks!"));

        assertThat(reply.getContent()).isEqualTo("thanks!");
        assertThat(reply.getOwner()).isEqualTo(chef);
    }

    @Test
    void createReviewReply_alreadyExists_throwsReviewReplyAlreadyExistsException() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(customer).rating(5).build();
        when(reviewRepository.findByUidAndDeletedFalse(review.getUid())).thenReturn(Optional.of(review));
        when(reviewReplyRepository.existsByReviewAndDeletedFalse(review)).thenReturn(true);

        assertThatThrownBy(() -> service.createReviewReply(review.getUid(), chef, new CreateReviewReplyRequest("x")))
                .isInstanceOf(ReviewReplyAlreadyExistsException.class);
    }

    @Test
    void updateReviewReply_notDishOwner_throwsPlainPermissionDenied_notNotDishOwner() {
        // PORT-NOTE regression: Django's update_review_reply raises a plain PermissionDeniedError
        // here, NOT NotDishOwnerException (unlike create_review_reply) -- same underlying "not the
        // dish owner" condition, two different exception classes depending on the endpoint.
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(customer).rating(5).build();
        ReviewReply reply = ReviewReply.builder().uid(UUID.randomUUID()).review(review).owner(chef).content("old").build();
        when(reviewReplyRepository.findByUidAndDeletedFalse(reply.getUid())).thenReturn(Optional.of(reply));

        assertThatThrownBy(() -> service.updateReviewReply(reply.getUid(), customer, new UpdateReviewReplyRequest("new")))
                .isInstanceOf(PermissionDeniedException.class)
                .isNotInstanceOf(NotDishOwnerException.class);
    }

    @Test
    void updateReviewReply_byDishOwner_succeeds() {
        Review review = Review.builder().uid(UUID.randomUUID()).dish(dish).owner(customer).rating(5).build();
        ReviewReply reply = ReviewReply.builder().uid(UUID.randomUUID()).review(review).owner(chef).content("old").build();
        when(reviewReplyRepository.findByUidAndDeletedFalse(reply.getUid())).thenReturn(Optional.of(reply));
        when(reviewReplyRepository.save(any(ReviewReply.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewReply updated = service.updateReviewReply(reply.getUid(), chef, new UpdateReviewReplyRequest("new content"));

        assertThat(updated.getContent()).isEqualTo("new content");
    }
}
