package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/reviews.py::OrderNotCompletedException. See {@link ReviewNotFoundException}'s javadoc for the file-wide status-code quirk. */
public class OrderNotCompletedException extends ApiException {
    public OrderNotCompletedException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "ORDER_NOT_COMPLETED", "Order is not completed, cannot review");
    }
}
