package com.amomeal.marketplace.profile.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/utils/exceptions.py::PermissionDeniedError as used by
 * {@code ProfileService.get_chef_profile} (not a {@code profile}-owned Django
 * exception file, but this generic is duplicated per-module in this port —
 * `menu` already has its own copy for the same reason).
 */
public class PermissionDeniedException extends ApiException {

    public PermissionDeniedException() {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You don't have permission to perform this action");
    }
}
