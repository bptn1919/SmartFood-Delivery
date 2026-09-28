package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/reviews.py::InvalidRatingException. See {@link ReviewNotFoundException}'s javadoc for the file-wide status-code quirk. */
public class InvalidRatingException extends ApiException {
    public InvalidRatingException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_RATING", "Rating must be an integer between 1 and 5");
    }
}
