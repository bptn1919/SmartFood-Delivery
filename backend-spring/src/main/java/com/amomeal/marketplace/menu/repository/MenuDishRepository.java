package com.amomeal.marketplace.menu.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.menu.entity.Menu;
import com.amomeal.marketplace.menu.entity.MenuDish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuDishRepository extends JpaRepository<MenuDish, Long> {

    boolean existsByMenuAndDish(Menu menu, Dish dish);

    Optional<MenuDish> findByMenuAndDish(Menu menu, Dish dish);

    /** Mirrors MenuORM.get_dish_menu_elements / get_all_dishes_in_menu (active=True only). */
    List<MenuDish> findAllByMenuAndActiveTrueOrderByPosition(Menu menu);

    /** Mirrors MenuORM.get_all_dishes_in_menu_for_chef when {@code active} is null:
     * all links regardless of active flag, excluding soft-deleted dishes. */
    List<MenuDish> findAllByMenuAndDishDeletedFalseOrderByPosition(Menu menu);

    /** Mirrors MenuORM.get_all_dishes_in_menu_for_chef when {@code active} is given. */
    List<MenuDish> findAllByMenuAndActiveAndDishDeletedFalseOrderByPosition(Menu menu, boolean active);
}
