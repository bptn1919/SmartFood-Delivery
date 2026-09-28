package com.amomeal.marketplace.review.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/review/models.py::Review (a Django {@code BaseModel} —
 * UUID {@code uid} PK + created_at/updated_at).
 *
 * <p>Reuses the already-ported {@link Dish}/{@link Order}/{@link CustomUser}/
 * {@link Attachment} entities from their owning modules rather than
 * redeclaring them (CLAUDE.md §8 task instructions) — {@code review} is a
 * pure consumer of all four.
 *
 * <p>FK cascade mirrors Django exactly: {@code dish} is {@code on_delete=CASCADE}
 * (a deleted dish takes its reviews with it); {@code order}/{@code owner}/
 * {@code attachment} are all {@code on_delete=SET_NULL}.
 *
 * <p>Django's {@code rating} field carries {@code MinValueValidator(1)}/
 * {@code MaxValueValidator(5)}, but those are Django <b>form/serializer-level</b>
 * validators, never enforced at the DB (no {@code CheckConstraint} in
 * {@code Meta}) or even at {@code Review.objects.create(...)} time (which never
 * calls {@code full_clean()}) — the real, load-bearing check is
 * {@code ReviewService.create_review}'s explicit
 * {@code if payload.rating < 1 or payload.rating > 5: raise InvalidRatingException}.
 * Ported the same way: validated in {@link com.amomeal.marketplace.review.service.ReviewService},
 * no DB-level CHECK constraint added (there would be nothing exercising it).
 *
 * <p>Django's {@code Meta.unique_together = [["owner", "dish", "order"]]} is
 * ported as a real Postgres unique index (see {@code V12__init_review.sql}) —
 * Postgres treats multiple NULLs in a unique index as distinct, matching
 * Django's own behavior for the nullable {@code order} column.
 */
@Entity
@Table(name = "review")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    private UUID uid;

    @Column(nullable = false)
    private int rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Builder.Default
    @Column(nullable = false)
    private double weight = 0.0;

    @Column(length = 100)
    private String issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_uid", nullable = false)
    private Dish dish;

    /** Django: {@code on_delete=SET_NULL}, nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_uid")
    private Order order;

    /** Django: {@code on_delete=SET_NULL}, nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

    /** Django: {@code on_delete=SET_NULL}, nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_uid")
    private Attachment attachment;

    @OneToOne(mappedBy = "review", fetch = FetchType.LAZY)
    private ReviewReply reply;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
