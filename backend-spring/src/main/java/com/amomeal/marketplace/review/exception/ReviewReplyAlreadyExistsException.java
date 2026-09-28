package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/reviews.py::ReviewReplyAlreadyExistsException. See {@link ReviewNotFoundException}'s javadoc for the file-wide status-code quirk. */
public class ReviewReplyAlreadyExistsException extends ApiException {
    public ReviewReplyAlreadyExistsException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "REVIEW_REPLY_ALREADY_EXISTS", "This review already has a reply");
    }
}
