package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::TopDishResponse — the
 * Bayesian-ranked "top dishes" row (GET /api/dishes/top,
 * FE-admin API_ENDPOINTS.TOP_DISHES).
 *
 * <p>{@code score} is the Bayesian average computed by
 * {@link com.amomeal.marketplace.dish.service.TopDishRanking}.
 */
public record TopDishResponse(
        UUID uid,
        String name,
        DishCategory category,
        String description,
        BigDecimal price,
        DishStatus status,
        @JsonProperty("avg_rating") double avgRating,
        @JsonProperty("final_score") double finalScore,
        @JsonProperty("is_suspended") boolean suspended,
        @JsonProperty("serving_size") int servingSize,
        @JsonProperty("location_id") Long locationId,
        @JsonProperty("chef_id") Long chefId,
        @JsonProperty("full_name_of_chef") String fullNameOfChef,
        @JsonProperty("public_url") String publicUrl,
        @JsonProperty("sold_count") long soldCount,
        @JsonProperty("review_count") long reviewCount,
        double score,
        @JsonProperty("in_stock") int inStock
) {

    public static TopDishResponse of(Dish dish, long soldCount, long reviewCount, double score, int inStock) {
        return new TopDishResponse(
                dish.getUid(),
                dish.getName(),
                dish.getCategory(),
                dish.getDescription(),
                dish.getPrice(),
                dish.getStatus(),
                dish.getAvgRating(),
                dish.getFinalScore(),
                dish.isSuspended(),
                dish.getServingSize(),
                dish.getLocation() == null ? null : dish.getLocation().getId(),
                dish.getOwner() == null ? null : dish.getOwner().getId(),
                DishResponse.resolveChefName(dish.getOwner()),
                dish.getAttachment() == null ? null : dish.getAttachment().getPublicUrl(),
                soldCount,
                reviewCount,
                score,
                inStock);
    }
}
