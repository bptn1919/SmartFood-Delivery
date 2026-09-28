package com.amomeal.marketplace.profile.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/profiles.py::FavouriteDishDoesNotExist. */
public class FavouriteDishDoesNotExistException extends ApiException {

    public FavouriteDishDoesNotExistException() {
        super(HttpStatus.NOT_FOUND, "FAVORITE_DISH_NOT_FOUND", "Favorite dish not found");
    }
}
