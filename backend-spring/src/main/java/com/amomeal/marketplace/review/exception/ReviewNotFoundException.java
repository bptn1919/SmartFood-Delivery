package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/reviews.py::ReviewNotFoundException.
 *
 * <p><b>PORT-NOTE — class-wide quirk in this Django file (same bug class as
 * {@code ingredient}'s {@code NutritionValidationException}, see PROGRESS.md):</b>
 * every exception in {@code exceptions/reviews.py} sets a class attribute named
 * {@code status_code} instead of {@code error_code}, the attribute
 * {@code utils/router/exception.py::APIException.error_code} (and the handler
 * that reads it) actually looks at. Since {@code status_code} is never read,
 * every one of these classes silently falls back to the base class default,
 * {@code HTTPStatus.INTERNAL_SERVER_ERROR} (500) — regardless of what its
 * {@code status_code} attribute claims. {@code message}/{@code message_code}
 * ARE real (correctly named), so the JSON body still carries the right
 * diagnostic code/text; only the HTTP transport status (and the body's
 * {@code error_code} field, which mirrors it) is wrong. Ported faithfully per
 * this task's explicit instruction: {@link org.springframework.http.HttpStatus#INTERNAL_SERVER_ERROR}
 * here, not the {@code HttpStatus.NOT_FOUND} the Python class visually suggests.
 */
public class ReviewNotFoundException extends ApiException {
    public ReviewNotFoundException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "REVIEW_DOES_NOT_EXIST", "Review does not exist");
    }
}
