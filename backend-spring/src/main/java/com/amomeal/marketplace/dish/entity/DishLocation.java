package com.amomeal.marketplace.dish.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors ../../backend/dish/models.py::DishLocation — a 3-level geography tree
 * (REGION -> SUBREGION -> COUNTRY). Django enforces the hierarchy in
 * {@code clean()} and auto-generates {@code slug} in {@code save()}; both are
 * ported into
 * {@link com.amomeal.marketplace.dish.service.DishLocationService} (slug
 * uniqueness needs a repository, so it can't live on the entity).
 *
 * <p>Django's parent FK is {@code on_delete=PROTECT}; the Flyway migration uses
 * {@code ON DELETE RESTRICT} and the delete path additionally raises
 * {@code DishLocationHasChildrenException} first, matching Django's own
 * explicit check.
 */
@Entity
@Table(name = "dish_location", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dish_location_name_parent", columnNames = {"name", "parent_id"}),
        @UniqueConstraint(name = "uk_dish_location_slug", columnNames = {"slug"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 255)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DishLocationType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DishLocation parent;

    @Builder.Default
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    private List<DishLocation> children = new ArrayList<>();
}
