package com.amomeal.marketplace.recommendation.entity;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.profile.entity.DietLevel;
import com.amomeal.marketplace.profile.entity.DietMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/recommendation/models.py::UserFoodPreferenceFeature — the
 * denormalized per-user preference snapshot rebuilt by {@code rebuild_user_feature}
 * (see {@link com.amomeal.marketplace.recommendation.service.RecommendationService#rebuildUserFeature}).
 *
 * <p>The id lists are JSON arrays of uid strings, exactly like Django's JSONField
 * ({@code _normalize_json_id} stringifies UUIDs before storing). The response schema exposes
 * {@code userId} as {@code "user"} (ninja ModelSchema FK naming, verified in the Django venv).
 *
 * <p>{@code embedding} is dead in Django (never read/written anywhere) — kept for field parity.
 */
@Entity
@Table(name = "user_food_preference_feature")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserFoodPreferenceFeature {

    @Id
    private UUID uid;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allergic_ingredient_ids", columnDefinition = "jsonb", nullable = false)
    private List<Object> allergicIngredientIds = new ArrayList<>();

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "diet_mode", nullable = false, length = 17)
    private DietMode dietMode = DietMode.NONE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "diet_level", nullable = false, length = 16)
    private DietLevel dietLevel = DietLevel.NONE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "allergy_mode", nullable = false, length = 16)
    private AllergyMode allergyMode = AllergyMode.WARN;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "favorite_ingredient_ids", columnDefinition = "jsonb", nullable = false)
    private List<Object> favoriteIngredientIds = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "favorite_dish_ids", columnDefinition = "jsonb", nullable = false)
    private List<Object> favoriteDishIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "embedding", columnDefinition = "double precision[]")
    private Double[] embedding;

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
