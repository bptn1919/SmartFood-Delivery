package com.amomeal.marketplace.recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/recommendation/models.py::DishRecipeSnapshot — audit trail of a
 * Gemini-generated recipe. {@code ingredients}: list of
 * {@code {"name": str, "ingredient_uid": str|null, "weight_g": float}}.
 */
@Entity
@Table(name = "dish_recipe_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishRecipeSnapshot {

    @Id
    private UUID uid;

    @Column(name = "dish_name", nullable = false, columnDefinition = "TEXT")
    private String dishName;

    @Column(name = "normalized_name", nullable = false, columnDefinition = "TEXT")
    private String normalizedName;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Object> ingredients = new ArrayList<>();

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String source = "GEMINI";

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

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
