package com.amomeal.marketplace.recommendation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mirrors ../../backend/recommendation/models.py::UserDailyNutrition — one row per
 * (user, date) (Django {@code unique_together}); column defaults identical to Django's.
 */
@Entity
@Table(name = "user_daily_nutrition", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_daily_nutrition_user_date", columnNames = {"user_id", "date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDailyNutrition {

    @Id
    private UUID uid;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(nullable = false)
    private int age = 25;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Gender gender = Gender.OTHER;

    @Builder.Default
    @Column(name = "height_cm", nullable = false)
    private double heightCm = 170.0;

    @Builder.Default
    @Column(name = "weight_kg", nullable = false)
    private double weightKg = 65.0;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "activity_level", nullable = false, length = 16)
    private ActivityLevel activityLevel = ActivityLevel.LIGHT;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Goal goal = Goal.MAINTAIN;

    @Column(name = "bmr_kcal", nullable = false)
    private double bmrKcal;
    @Column(name = "tdee_kcal", nullable = false)
    private double tdeeKcal;

    @Column(name = "target_protein_g", nullable = false)
    private double targetProteinG;
    @Column(name = "target_lipid_g", nullable = false)
    private double targetLipidG;
    @Column(name = "target_carb_g", nullable = false)
    private double targetCarbG;
    @Column(name = "target_sodium_mg", nullable = false)
    private double targetSodiumMg;
    @Column(name = "target_fiber_g", nullable = false)
    private double targetFiberG;

    @Column(name = "consumed_protein_g", nullable = false)
    private double consumedProteinG;
    @Column(name = "consumed_lipid_g", nullable = false)
    private double consumedLipidG;
    @Column(name = "consumed_carb_g", nullable = false)
    private double consumedCarbG;
    @Column(name = "consumed_sodium_mg", nullable = false)
    private double consumedSodiumMg;
    @Column(name = "consumed_fiber_g", nullable = false)
    private double consumedFiberG;

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
