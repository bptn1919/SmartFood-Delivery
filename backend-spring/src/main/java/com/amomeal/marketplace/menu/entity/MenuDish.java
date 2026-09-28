package com.amomeal.marketplace.menu.entity;

import com.amomeal.marketplace.dish.entity.Dish;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirrors ../../backend/menu/models.py::MenuDish — a plain {@code models.Model}
 * in Django (NOT a {@code BaseModel}), so it gets Django's default
 * auto-increment integer PK rather than a {@code uid}, unlike every other
 * entity in this port.
 *
 * <p>Reuses the already-ported {@link Dish} entity/repository from the `dish`
 * module rather than redeclaring it (CLAUDE.md §8 task instructions).
 *
 * <p>FK cascade: both {@code menu} and {@code dish} are
 * {@code on_delete=CASCADE} in Django.
 */
@Entity
@Table(name = "menu_dish", uniqueConstraints = {
        @UniqueConstraint(name = "uk_menu_dish_menu_dish", columnNames = {"menu_id", "dish_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuDish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", referencedColumnName = "uid", nullable = false)
    private Menu menu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dish_id", referencedColumnName = "uid")
    private Dish dish;

    @Builder.Default
    @Column(nullable = false)
    private int position = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
