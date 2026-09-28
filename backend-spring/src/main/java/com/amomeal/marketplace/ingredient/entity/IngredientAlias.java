package com.amomeal.marketplace.ingredient.entity;

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
 * Mirrors ../../backend/ingredient/orm/ingredient.py::IngredientAlias — an
 * alternate name that resolves search/autocomplete to a canonical
 * {@link Ingredient}. {@code aliasNoAccent} carries Django's
 * {@code unique_ingredient_alias_no_accent} constraint.
 */
@Entity
@Table(name = "ingredient_alias", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ingredient_alias_no_accent", columnNames = "alias_no_accent")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngredientAlias {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_uid", nullable = false)
    private Ingredient ingredient;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String alias;

    @Column(name = "alias_no_accent", nullable = false, columnDefinition = "TEXT")
    private String aliasNoAccent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private CustomUser createdBy;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
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
        if (alias != null) {
            aliasNoAccent = com.amomeal.marketplace.ingredient.service.RemoveAccents.apply(alias);
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (alias != null) {
            aliasNoAccent = com.amomeal.marketplace.ingredient.service.RemoveAccents.apply(alias);
        }
        updatedAt = Instant.now();
    }
}
