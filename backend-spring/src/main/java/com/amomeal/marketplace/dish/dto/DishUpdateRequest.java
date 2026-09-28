package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/requests.py::DishUpdateSchema — every field
 * optional, and Django applies it with {@code exclude_none=True} (a null field
 * means "leave unchanged"), except {@code location_id}, whose null IS meaningful
 * ("clear the location"). See DishService.updateDish.
 */
public record DishUpdateRequest(
        String name,
        DishCategory category,
        String description,
        @Positive BigDecimal price,
        DishStatus status,
        @JsonProperty("attachment_uid") UUID attachmentUid,
        @JsonProperty("location_id") Long locationId,
        @JsonProperty("serving_size") Integer servingSize
) {
}
