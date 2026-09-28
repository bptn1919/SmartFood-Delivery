package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors ../../backend/exceptions/nutrition.py::NutritionOutlierError —
 * Layer 3: the dish's per-serving nutrition totals exceed the biological
 * ceilings in {@code NUTRITION_BOUNDS}.
 *
 * <p>Detail replicates Django's {@code {"serving_size": .., "violations": [..]}}
 * shape; each violation entry is built by
 * {@link com.amomeal.marketplace.dish.service.NutritionValidation}.
 */
public class NutritionOutlierException extends ApiException {

    public NutritionOutlierException(List<Map<String, Object>> violations, int servingSize) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "NUTRITION_OUTLIER",
                "Kết quả dinh dưỡng của món ăn vượt ngưỡng hợp lý. Vui lòng kiểm tra lại khối lượng nguyên liệu.",
                buildDetail(violations, servingSize));
    }

    private static Map<String, Object> buildDetail(List<Map<String, Object>> violations, int servingSize) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("serving_size", servingSize);
        detail.put("violations", violations);
        return detail;
    }
}
