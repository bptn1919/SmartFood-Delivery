package com.amomeal.marketplace.dish.entity;

import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/models.py::DishAlias — alternative names for a
 * dish, used by {@link com.amomeal.marketplace.dish.service.DishSearchService}'s
 * exact-alias and fuzzy-alias retrieval stages.
 *
 * <p>Django auto-derives {@code alias_name_no_accent} in {@code save()} —
 * replicated in the JPA lifecycle callbacks below, reusing the already-ported
 * {@link RemoveAccents} from the `ingredient` module.
 */
@Entity
@Table(name = "dish_alias", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dish_alias_dish_name_no_accent", columnNames = {"dish_uid", "alias_name_no_accent"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishAlias {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_uid", nullable = false)
    private Dish dish;

    @Column(name = "alias_name", nullable = false, columnDefinition = "TEXT")
    private String aliasName;

    @Column(name = "alias_name_no_accent", nullable = false, columnDefinition = "TEXT")
    private String aliasNameNoAccent;

    @Builder.Default
    @Column(name = "similarity_score", nullable = false)
    private double similarityScore = 0.9;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "alias_type", nullable = false, length = 20)
    private DishAliasType aliasType = DishAliasType.SYNONYM;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        if (aliasName != null) {
            aliasNameNoAccent = RemoveAccents.apply(aliasName);
        }
        createdAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        if (aliasName != null) {
            aliasNameNoAccent = RemoveAccents.apply(aliasName);
        }
    }
}
