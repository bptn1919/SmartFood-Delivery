package com.amomeal.marketplace.menu.dto;

import com.amomeal.marketplace.menu.entity.MenuDish;

import java.util.UUID;

/**
 * Mirrors ../../backend/menu/schemas/responses.py::MenuDishResponse
 * ({@code fields = "__all__"} on the {@code MenuDish} model: id, menu, dish,
 * position, active). {@code menu}/{@code dish} are Django Ninja's default FK
 * representation — the related row's {@code to_field} value, i.e. each side's
 * {@code uid} (Django declares both FKs with {@code to_field="uid"}).
 */
public record MenuDishResponse(
        Long id,
        UUID menu,
        UUID dish,
        int position,
        boolean active
) {
    public static MenuDishResponse of(MenuDish menuDish) {
        return new MenuDishResponse(
                menuDish.getId(),
                menuDish.getMenu().getUid(),
                menuDish.getDish() == null ? null : menuDish.getDish().getUid(),
                menuDish.getPosition(),
                menuDish.isActive());
    }
}
