package com.amomeal.marketplace.cart.entity;

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
 * Mirrors ../../backend/cart/models.py::Cart (BaseModel + a nullable
 * one-to-one {@code owner}). Django declares {@code owner} nullable/blank
 * with {@code on_delete=SET_NULL} even though every real row always has one
 * (created lazily by {@code CartORM.get_cart_by_user}'s
 * {@code get_or_create(owner=user)}) — ported faithfully rather than made
 * non-null, so a deleted {@code CustomUser} row doesn't cascade-delete an
 * otherwise-orphaned cart.
 */
@Entity
@Table(name = "cart", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_owner", columnNames = "owner_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    @Id
    private UUID uid;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

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
