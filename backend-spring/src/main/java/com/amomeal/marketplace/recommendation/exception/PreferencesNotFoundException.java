package com.amomeal.marketplace.recommendation.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/recommendation.py::PreferencesNotFoundException.
 *
 * <p>PORT-NOTE (faithful, same bug class as every class in exceptions/reviews.py): Django sets
 * {@code status_code = HTTPStatus.NOT_FOUND}, but the base {@code APIException} and its handler
 * only read {@code error_code} (default 500). So Django really answers <b>500</b> (transport status
 * and body {@code error_code}) with {@code message_code=PREFERENCES_DOES_NOT_EXIST} — not the 404
 * it looks like. Ported as 500; flagged in PROGRESS.md.
 */
public class PreferencesNotFoundException extends ApiException {

    public PreferencesNotFoundException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "PREFERENCES_DOES_NOT_EXIST", "Preferences do not exist");
    }
}
