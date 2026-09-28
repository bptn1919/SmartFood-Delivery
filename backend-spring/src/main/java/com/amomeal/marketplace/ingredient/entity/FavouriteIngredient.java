package com.amomeal.marketplace.ingredient.entity;

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
 * Mirrors ../../backend/ingredient/orm/ingredient.py::FavouriteIngredient — a
 * per-user "I like this ingredient" signal, unique per (ingredient, user),
 * soft-deleted rather than hard-deleted so history is preserved (Django
 * comment: "không cho hard delete để giữ lịch sử yêu thích"). Consumed by
 * `recommendation` (../../backend/recommendation/services/recommendation.py)
 * and `profile` (../../backend/profile/services/__init__.py) — confirmed by
 * grep; both read {@code user_id}/{@code ingredient_uid}/{@code deleted} only,
 * which this entity's column names match.
 */
@Entity
@Table(name = "favourite_ingredient", uniqueConstraints = {
        @UniqueConstraint(name = "uk_favourite_ingredient_user_ingredient", columnNames = {"ingredient_uid", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FavouriteIngredient {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_uid", nullable = false)
    private Ingredient ingredient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private CustomUser user;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false)
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
