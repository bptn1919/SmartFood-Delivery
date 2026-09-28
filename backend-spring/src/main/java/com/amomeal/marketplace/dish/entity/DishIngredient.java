package com.amomeal.marketplace.dish.entity;

import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
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
 * Mirrors ../../backend/dish/models.py::DishIngredient — one ingredient line of
 * a dish, carrying its own denormalized nutrition snapshot (scaled from the
 * `ingredient` row's per-reference-weight values, or entered by the chef).
 *
 * <p>References the already-ported
 * {@link com.amomeal.marketplace.ingredient.entity.Ingredient} /
 * {@link IngredientSuggestion} entities directly (both nullable, because a chef
 * can add a custom, not-yet-approved ingredient by name).
 *
 * <p>FK cascade behavior mirrors Django: dish -&gt; CASCADE, ingredient -&gt;
 * RESTRICT, suggestion/created_by/updated_by -&gt; SET NULL. Django's
 * {@code unique_together = (dish, ingredient)} is ported as-is — note it only
 * constrains rows with a non-null ingredient (Postgres treats NULLs as
 * distinct), which is exactly what Django gets too.
 */
@Entity
@Table(name = "dish_ingredient", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dish_ingredient_dish_ingredient", columnNames = {"dish_uid", "ingredient_uid"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishIngredient {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_uid", nullable = false)
    private Dish dish;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_uid")
    private Ingredient ingredient;

    @Column(name = "custom_name", columnDefinition = "TEXT")
    private String customName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngredientSource source = IngredientSource.USDA;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 16)
    private IngredientImportStatus approvalStatus = IngredientImportStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggestion_uid")
    private IngredientSuggestion suggestion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private CustomUser createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private CustomUser updatedBy;

    private Double weight;
    private Double energy;
    private Double protein;
    private Double lipid;
    private Double carbohydrate;
    private Double fiber;
    private Double natri;
    private Double kali;
    private Double cholesterol;
    private Double retinol;
    private Double caroten;

    @Column(name = "vitamin_b_1")
    private Double vitaminB1;

    @Column(name = "vitamin_b_2")
    private Double vitaminB2;

    @Column(name = "vitamin_pp")
    private Double vitaminPp;

    @Column(name = "vitamin_c")
    private Double vitaminC;

    private Double calcium;
    private Double phosphorus;
    private Double fe;
    private Double mg;
    private Double zn;

    /** Django: FloatField(default=1.0, null=True) — 0..1 data-quality score. */
    @Builder.Default
    private Double confidence = 1.0;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Mirrors Django's {@code computed_ingredient_name} property. */
    @Transient
    public String getComputedIngredientName() {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        return ingredient != null ? ingredient.getName() : "Unknown";
    }

    /** Mirrors Django's {@code computed_ingredient_uid} property. */
    @Transient
    public UUID getComputedIngredientUid() {
        return ingredient != null ? ingredient.getUid() : null;
    }

    /** Mirrors the {@code is_custom} annotation in DishORM.get_all_ingredients_of_dish_for_chefs. */
    @Transient
    public boolean isCustomIngredient() {
        return ingredient == null;
    }

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
