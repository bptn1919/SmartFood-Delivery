package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors ../../backend/exceptions/nutrition.py::IngredientWeightOutOfBounds —
 * Layer 1 of the three-layer nutrition validation
 * ({@link com.amomeal.marketplace.dish.service.NutritionValidation}): a single
 * DishIngredient's weight is outside its ingredient category's bounds.
 *
 * <p>Lives in {@code dish/exception/} rather than a {@code nutrition/} package
 * because {@code backend/exceptions/nutrition.py} has no Django app of its own —
 * it is raised exclusively from {@code dish/services/validation.py}.
 *
 * <p>The detail payload replicates Django's constructor exactly, including
 * omitting {@code min_allowed_g}/{@code max_allowed_g} when they are None, and
 * the insertion order of the keys.
 */
public class IngredientWeightOutOfBoundsException extends ApiException {

    public IngredientWeightOutOfBoundsException(String ingredientName, double weight, String category,
                                                Double minG, Double maxG) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INGREDIENT_WEIGHT_OUT_OF_BOUNDS",
                "Khối lượng nguyên liệu vượt giới hạn cho phép của danh mục này.",
                buildDetail(ingredientName, weight, category, minG, maxG));
    }

    private static Map<String, Object> buildDetail(String ingredientName, double weight, String category,
                                                   Double minG, Double maxG) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ingredient", ingredientName);
        detail.put("entered_weight_g", weight);
        detail.put("category", category);
        if (minG != null) {
            detail.put("min_allowed_g", minG);
        }
        if (maxG != null) {
            detail.put("max_allowed_g", maxG);
        }
        return detail;
    }
}
