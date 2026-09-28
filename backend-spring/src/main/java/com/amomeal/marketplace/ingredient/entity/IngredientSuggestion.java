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
 * Mirrors ../../backend/ingredient/orm/ingredient.py::IngredientSuggestion — the
 * chef-facing moderation queue for new ingredient names/categories. In Django
 * these are created by the {@code dish} app when a chef types a custom
 * ingredient name that doesn't resolve to an existing {@link Ingredient}
 * (../../backend/ingredient/services/__init__.py::IngredientSuggestionService.create_suggestion),
 * and {@code approve_new}/{@code approve_alias} resolve them by pulling nutrition
 * data from the originating {@code DishIngredient} row. The {@code dish} module
 * isn't ported yet (ingredient ports first per CLAUDE.md §7) — see PORT-NOTEs on
 * {@link com.amomeal.marketplace.ingredient.service.IngredientSuggestionService}
 * for exactly what's deferred and PROGRESS.md for the flagged summary.
 */
@Entity
@Table(name = "ingredient_suggestion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngredientSuggestion {

    @Id
    private UUID uid;

    @Column(name = "suggested_name", nullable = false, columnDefinition = "TEXT")
    private String suggestedName;

    @Column(name = "suggested_name_no_accent", nullable = false, columnDefinition = "TEXT")
    private String suggestedNameNoAccent;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_category", length = 16)
    private IngredientCategory suggestedCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private CustomUser createdBy;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IngredientImportStatus status = IngredientImportStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_id")
    private CustomUser verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_uid")
    private Ingredient ingredient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_alias_uid")
    private IngredientAlias resolvedAlias;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_uid")
    private Attachment attachment;

    @Builder.Default
    @Column(nullable = false)
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
        if (suggestedName != null) {
            suggestedNameNoAccent = com.amomeal.marketplace.ingredient.service.RemoveAccents.apply(suggestedName);
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (suggestedName != null) {
            suggestedNameNoAccent = com.amomeal.marketplace.ingredient.service.RemoveAccents.apply(suggestedName);
        }
        updatedAt = Instant.now();
    }
}
