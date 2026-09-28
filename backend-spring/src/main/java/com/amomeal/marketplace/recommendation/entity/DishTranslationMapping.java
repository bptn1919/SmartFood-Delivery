package com.amomeal.marketplace.recommendation.entity;

import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/recommendation/models.py::DishTranslationMapping — free-text Vietnamese
 * dish name to nutrition per serving. {@code normalizedVietnameseName} is derived on every save
 * exactly like Django's {@code save()} override ({@code remove_accents(vietnamese_name.strip())}).
 * No endpoint writes these rows (Django neither) — they are seeded data.
 */
@Entity
@Table(name = "dish_translation_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishTranslationMapping {

    @Id
    private UUID uid;

    @Column(name = "vietnamese_name", nullable = false, unique = true, columnDefinition = "TEXT")
    private String vietnameseName;

    @Column(name = "normalized_vietnamese_name", nullable = false, columnDefinition = "TEXT")
    private String normalizedVietnameseName;

    @Column(name = "english_name", columnDefinition = "TEXT")
    private String englishName;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Object> ingredients = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nutrition_per_serving", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> nutritionPerServing = new LinkedHashMap<>();

    @Builder.Default
    @Column(name = "serving_grams", nullable = false)
    private double servingGrams = 100.0;

    @Builder.Default
    @Column(name = "usda_confidence", nullable = false)
    private double usdaConfidence = 0.8;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        normalize();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        normalize();
        updatedAt = Instant.now();
    }

    private void normalize() {
        normalizedVietnameseName = RemoveAccents.apply(vietnameseName == null ? "" : vietnameseName.strip());
    }
}
