package com.amomeal.marketplace.profile.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/profiles.py::ProfileDoesNotExist. */
public class ProfileDoesNotExistException extends ApiException {

    public ProfileDoesNotExistException() {
        super(HttpStatus.NOT_FOUND, "PROFILE_DOES_NOT_EXIST", "Profile does not exist");
    }
}
