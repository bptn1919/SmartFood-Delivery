package com.amomeal.marketplace.recommendation.entity;

import com.amomeal.marketplace.dish.entity.Dish;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/recommendation/models.py::DailyMealLog. {@code rawPayload} is a JSON
 * object (Django JSONField(default=dict)); {@code sourceRef} is the idempotency key used when
 * syncing completed app orders ({@code "order_item:<id>"}).
 */
@Entity
@Table(name = "daily_meal_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyMealLog {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_nutrition_uid", nullable = false)
    private UserDailyNutrition dailyNutrition;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MealSource source = MealSource.PARSED;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_time", nullable = false, length = 16)
    private MealTime mealTime = MealTime.UNKNOWN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dish_uid")
    private Dish dish;

    @Column(name = "meal_name", nullable = false, columnDefinition = "TEXT")
    private String mealName;

    @Builder.Default
    @Column(name = "quantity_multiplier", nullable = false)
    private double quantityMultiplier = 1.0;

    @Column(name = "nutrition_protein_g", nullable = false)
    private double nutritionProteinG;
    @Column(name = "nutrition_lipid_g", nullable = false)
    private double nutritionLipidG;
    @Column(name = "nutrition_carb_g", nullable = false)
    private double nutritionCarbG;
    @Column(name = "nutrition_sodium_mg", nullable = false)
    private double nutritionSodiumMg;
    @Column(name = "nutrition_fiber_g", nullable = false)
    private double nutritionFiberG;

    @Builder.Default
    @Column(name = "confidence_parse", nullable = false)
    private double confidenceParse = 1.0;

    @Builder.Default
    @Column(name = "confidence_source", nullable = false)
    private double confidenceSource = 1.0;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawPayload = new LinkedHashMap<>();

    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
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
