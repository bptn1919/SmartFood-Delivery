package com.amomeal.marketplace.cart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Mirrors ../../backend/cart/schemas/responses.py::CartItemResponse (the
 * per-item dict built by {@code CartORM.get_cart_detail}).
 *
 * <p>{@code @JsonProperty("is_selected")} is required here (unlike this
 * port's other plain camelCase fields) because the mechanical
 * camelCase-&gt;snake_case Jackson strategy would turn a component literally
 * named {@code selected} into JSON key {@code selected}, not {@code
 * is_selected} — same precedent as {@code dish.dto.DishResponse}'s {@code
 * is_suspended}/{@code is_favorite} fields.
 */
public record CartItemResponse(
        UUID uid,
        UUID dishUid,
        String dishName,
        String imageUrl,
        double price,
        int quantity,
        LocalDate deliveryDate,
        @JsonProperty("is_selected") boolean selected,
        double subtotal
) {
}
