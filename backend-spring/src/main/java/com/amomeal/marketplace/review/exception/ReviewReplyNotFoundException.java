package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/reviews.py::ReviewReplyNotFoundException. See {@link ReviewNotFoundException}'s javadoc for the file-wide status-code quirk. */
public class ReviewReplyNotFoundException extends ApiException {
    public ReviewReplyNotFoundException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "REVIEW_REPLY_NOT_FOUND", "Review reply does not exist");
    }
}
