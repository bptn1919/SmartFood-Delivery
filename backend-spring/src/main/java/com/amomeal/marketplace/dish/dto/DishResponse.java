package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishLocation;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishResponse (and
 * DishListResponse, which is the same shape minus the two allergy fields — this
 * port uses one record and simply leaves those at their defaults for list
 * endpoints, matching what the FE receives either way).
 *
 * <p>Field names are snake_case to match what FE-admin reads
 * (FE-admin/src/services/dishService.js logs {@code location}/{@code location_id}
 * explicitly, and Dishes.jsx reads {@code public_url}/{@code sold_count}).
 *
 * <p>{@code location} is Django's resolver output: the ancestor chain joined
 * root-first with " - " (e.g. {@code "Asia - Southeast Asia - Vietnam"}).
 */
public record DishResponse(
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
        @JsonProperty("is_favorite") boolean favorite,
        @JsonProperty("allergy_warning") boolean allergyWarning,
        @JsonProperty("allergen_ingredients") List<String> allergenIngredients,
        @JsonProperty("full_name_of_chef") String fullNameOfChef,
        @JsonProperty("public_url") String publicUrl,
        String location,
        @JsonProperty("location_id") Long locationId,
        @JsonProperty("sold_count") long soldCount,
        @JsonProperty("in_stock") int inStock
) {

    public static DishResponse of(Dish dish, boolean favorite, boolean allergyWarning,
                                  List<String> allergenIngredients, long soldCount, int inStock) {
        return new DishResponse(
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
                favorite,
                allergyWarning,
                allergenIngredients == null ? List.of() : allergenIngredients,
                resolveChefName(dish.getOwner()),
                dish.getAttachment() == null ? null : dish.getAttachment().getPublicUrl(),
                resolveLocationPath(dish.getLocation()),
                dish.getLocation() == null ? null : dish.getLocation().getId(),
                soldCount,
                inStock);
    }

    /** Django: {@code owner.get_full_name() or owner.username}. */
    public static String resolveChefName(CustomUser owner) {
        if (owner == null) {
            return null;
        }
        String first = owner.getFirstName() == null ? "" : owner.getFirstName().trim();
        String last = owner.getLastName() == null ? "" : owner.getLastName().trim();
        String fullName = (first + " " + last).trim();
        return fullName.isEmpty() ? owner.getUsername() : fullName;
    }

    /** Django: walk {@code parent} up, then {@code " - ".join(reversed(names))}. */
    public static String resolveLocationPath(DishLocation location) {
        if (location == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        DishLocation current = location;
        while (current != null) {
            names.add(current.getName());
            current = current.getParent();
        }
        Collections.reverse(names);
        return String.join(" - ", names);
    }
}
