package com.amomeal.marketplace.ingredient.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
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
 * Mirrors ../../backend/ingredient/orm/ingredient.py::Ingredient (BaseModel +
 * name/category/nutrition fields + owner/updater/attachment FKs). {@code
 * nameNoAccent} is derived (Django's {@code save()} override calls {@code
 * remove_accents(name)}) — replicated here in {@link #onCreate()}/{@link
 * #onUpdate()}, see {@link com.amomeal.marketplace.ingredient.service.RemoveAccents}.
 *
 * <p>Nutrition fields are all nullable Doubles (Django {@code FloatField(null=True)})
 * expressed per the Django comments as: weight(g), energy(kcal), protein/lipid/
 * carbohydrate/fiber(g), natri/kali/cholesterol(mg-ish, Django leaves it a plain
 * float with no unit enforcement), retinol/caroten(µg), and the remaining
 * vitamin/mineral fields with no unit comment in Django either — ported as-is.
 */
@Entity
@Table(name = "ingredient")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ingredient {

    @Id
    private UUID uid;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "name_no_accent", nullable = false, columnDefinition = "TEXT")
    private String nameNoAccent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IngredientCategory category;

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

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private IngredientSource source = IngredientSource.USDA;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updater_id")
    private CustomUser updater;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_uid")
    private Attachment attachment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        if (name != null) {
            nameNoAccent = com.amomeal.marketplace.ingredient.service.RemoveAccents.apply(name);
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (name != null) {
            nameNoAccent = com.amomeal.marketplace.ingredient.service.RemoveAccents.apply(name);
        }
        updatedAt = Instant.now();
    }
}
