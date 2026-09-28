package com.amomeal.marketplace.menu.dto;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.amomeal.marketplace.menu.entity.MenuDish;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirrors the plain-dict payload built by
 * ../../backend/menu/orm/menu.py::MenuORM.get_all_dishes_in_menu_for_chef
 * (uid/name/category/description/price/status/avg_rating/active/position/
 * public_url) — the chef-facing "all dishes in this menu" listing, which is
 * NOT the same shape as the customer-facing {@code DishResponse}.
 */
public record MenuDishDetailResponse(
        UUID uid,
        String name,
        DishCategory category,
        String description,
        BigDecimal price,
        DishStatus status,
        @JsonProperty("avg_rating") double avgRating,
        boolean active,
        int position,
        @JsonProperty("public_url") String publicUrl
) {
    public static MenuDishDetailResponse of(MenuDish menuDish) {
        Dish dish = menuDish.getDish();
        return new MenuDishDetailResponse(
                dish.getUid(),
                dish.getName(),
                dish.getCategory(),
                dish.getDescription(),
                dish.getPrice(),
                dish.getStatus(),
                dish.getAvgRating(),
                menuDish.isActive(),
                menuDish.getPosition(),
                dish.getAttachment() == null ? null : dish.getAttachment().getPublicUrl());
    }
}
