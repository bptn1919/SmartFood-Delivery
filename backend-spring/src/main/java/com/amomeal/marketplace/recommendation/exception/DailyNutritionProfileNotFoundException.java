package com.amomeal.marketplace.recommendation.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/recommendation.py::DailyNutritionProfileNotFoundException.
 *
 * <p>PORT-NOTE (faithful): like {@link PreferencesNotFoundException}, Django sets
 * {@code status_code} instead of {@code error_code}, so the real response is <b>500</b> with
 * {@code message_code=DAILY_NUTRITION_PROFILE_NOT_FOUND}. Raised by
 * {@code GET/PATCH /me/daily-nutrition/profile} when today's profile row does not exist.
 */
public class DailyNutritionProfileNotFoundException extends ApiException {

    public DailyNutritionProfileNotFoundException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "DAILY_NUTRITION_PROFILE_NOT_FOUND", "Daily nutrition profile not found");
    }
}
