package com.amomeal.marketplace.dish.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Mirrors ../../backend/dish/models.py::DishAvailability — the per-day
 * inventory row (one per dish+date, Django {@code unique_together}). This is
 * the permanent source of truth that
 * {@link com.amomeal.marketplace.dish.service.StockReservationService} seeds
 * its Redis counter from, and that {@code confirm()} permanently deducts.
 */
@Entity
@Table(name = "dish_availability", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dish_availability_dish_date", columnNames = {"dish_uid", "available_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_uid", nullable = false)
    private Dish dish;

    @Column(name = "available_date", nullable = false)
    private LocalDate availableDate;

    @Builder.Default
    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    /** Django: PositiveIntegerField(default=0). */
    @Builder.Default
    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity = 0;

    @Column(columnDefinition = "TEXT")
    private String note;

    /**
     * backend-edit 9939179 ({@code updated_at = DateTimeField(auto_now=True)}, migration 0023;
     * Flyway V19 here): durable trace of "this (dish, date) row changed" — the post-Redis-outage
     * counter resync ({@code StockRedisGateway}) uses it to find counters made stale by a chef
     * edit that could not reach Redis. Bumped on every entity save, like Django's auto_now.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = Instant.now();
    }
}
