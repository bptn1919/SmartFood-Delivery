package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/reviews.py::DuplicateReviewException. See {@link ReviewNotFoundException}'s javadoc for the file-wide status-code quirk. */
public class DuplicateReviewException extends ApiException {
    public DuplicateReviewException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "DUPLICATE_REVIEW", "You have already reviewed this dish in this order");
    }
}
