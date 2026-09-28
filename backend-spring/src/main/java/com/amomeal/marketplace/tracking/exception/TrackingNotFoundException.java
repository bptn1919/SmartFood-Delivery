package com.amomeal.marketplace.tracking.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * PORT-NOTE: Django's order-tracking endpoint answers a missing order / location with an unhandled
 * {@code DoesNotExist} (500); it is only reachable here because that endpoint is fixed (see
 * {@code app.tracking.preserve-order-tracking-bug}), so a proper 404 is used.
 */
public class TrackingNotFoundException extends ApiException {
    public TrackingNotFoundException(String messageCode, String message) {
        super(HttpStatus.NOT_FOUND, messageCode, message);
    }
}
