package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/reviews.py::AIModelInvalidResponseException.
 * Dead code — see {@link AIModelUnavailableException}'s javadoc; never thrown
 * by {@link com.amomeal.marketplace.review.service.ReviewService}.
 */
public class AIModelInvalidResponseException extends ApiException {
    public AIModelInvalidResponseException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "AI_MODEL_INVALID_RESPONSE", "AI model returned an invalid response");
    }
}
