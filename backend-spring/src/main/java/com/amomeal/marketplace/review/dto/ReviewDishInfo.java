package com.amomeal.marketplace.review.dto;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;

import java.util.UUID;

/** Mirrors ../../backend/review/schemas/responses.py::ReviewDishSchema. */
public record ReviewDishInfo(UUID uid, String name, DishCategory category, double price) {

    public static ReviewDishInfo of(Dish dish) {
        if (dish == null) {
            return null;
        }
        return new ReviewDishInfo(dish.getUid(), dish.getName(), dish.getCategory(), dish.getPrice().doubleValue());
    }
}
