package com.amomeal.marketplace.review.entity;

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
 * Mirrors ../../backend/review/models.py::ReviewReply — a Django
 * {@code BaseModel} with a {@code OneToOneField(Review, on_delete=CASCADE)},
 * i.e. at most one reply per review, written by the dish's owning chef.
 */
@Entity
@Table(name = "review_reply")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReply {

    @Id
    private UUID uid;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_uid", nullable = false, unique = true)
    private Review review;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Django: {@code on_delete=SET_NULL}, nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

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
