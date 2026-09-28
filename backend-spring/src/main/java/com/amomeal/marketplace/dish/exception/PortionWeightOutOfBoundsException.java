package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import com.amomeal.marketplace.dish.service.DishNutritionService;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors ../../backend/exceptions/nutrition.py::PortionWeightOutOfBounds —
 * Layer 2: the dish's total portion weight is outside
 * {@code [MIN_PORTION_WEIGHT, MAX_PORTION_WEIGHT]}.
 *
 * <p>Detail replicates Django's constructor exactly: {@code total_weight_g}
 * rounded to 1 decimal, plus whichever bound was actually violated.
 */
public class PortionWeightOutOfBoundsException extends ApiException {

    public PortionWeightOutOfBoundsException(double totalG, Double minG, Double maxG) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PORTION_WEIGHT_OUT_OF_BOUNDS",
                "Tổng khối lượng khẩu phần không hợp lý.",
                buildDetail(totalG, minG, maxG));
    }

    private static Map<String, Object> buildDetail(double totalG, Double minG, Double maxG) {
        Map<String, Object> detail = new LinkedHashMap<>();
        // Django: round(total_g, 1) — Python half-to-even, not Math.round half-up.
        detail.put("total_weight_g", DishNutritionService.pyRound(totalG, 1));
        if (minG != null) {
            detail.put("min_allowed_g", minG);
        }
        if (maxG != null) {
            detail.put("max_allowed_g", maxG);
        }
        return detail;
    }
}
