package com.amomeal.marketplace.profile.entity;

import com.amomeal.marketplace.dish.entity.Dish;
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
 * Mirrors ../../backend/profile/models.py::CustomerFavoriteDish (BaseModel +
 * unique_together user/dish, soft-deleted). Reuses `dish`'s already-ported
 * {@link Dish} entity rather than redeclaring it.
 */
@Entity
@Table(name = "customer_favorite_dish", uniqueConstraints = {
        @UniqueConstraint(name = "uk_customer_favorite_dish_user_dish", columnNames = {"user_id", "dish_uid"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerFavoriteDish {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private CustomUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_uid", nullable = false)
    private Dish dish;

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
