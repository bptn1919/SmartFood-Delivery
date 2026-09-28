package com.amomeal.marketplace.profile.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/profiles.py::CustomerAddressNotFoundException.
 * PORT-NOTE: dead code in Django too — grepping profile/services/__init__.py
 * shows every address "not found" path actually raises {@code ProfileDoesNotExist}
 * instead of this class. Ported anyway per CLAUDE.md §4 (one class per Django
 * exception), never thrown by this port either, matching Django's own behavior.
 */
public class CustomerAddressNotFoundException extends ApiException {

    public CustomerAddressNotFoundException() {
        super(HttpStatus.NOT_FOUND, "CUSTOMER_ADDRESS_NOT_FOUND", "Customer address not found");
    }
}
