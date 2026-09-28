package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** Mirrors ../../backend/dish/schemas/requests.py::DishWithAttachmentSchema. */
public record DishCreateRequest(
        @NotBlank String name,
        @NotNull DishCategory category,
        String description,
        @NotNull @Positive BigDecimal price,
        DishStatus status,
        @JsonProperty("attachment_uid") @NotNull UUID attachmentUid,
        @JsonProperty("location_id") Long locationId,
        @JsonProperty("serving_size") Integer servingSize
) {
    public DishStatus statusOrDefault() {
        return status == null ? DishStatus.AVAILABLE : status;
    }
}
