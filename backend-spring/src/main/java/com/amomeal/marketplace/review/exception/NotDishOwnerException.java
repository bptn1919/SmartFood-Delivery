package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/reviews.py::NotDishOwnerException. See {@link ReviewNotFoundException}'s javadoc for the file-wide status-code quirk. */
public class NotDishOwnerException extends ApiException {
    public NotDishOwnerException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "NOT_DISH_OWNER", "Only the dish owner (chef) can reply to reviews");
    }
}
