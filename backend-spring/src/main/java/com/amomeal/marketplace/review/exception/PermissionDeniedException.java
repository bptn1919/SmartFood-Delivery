package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/utils/exceptions.py::PermissionDeniedError as used by
 * {@code ReviewService} (not a {@code review}-owned Django exception file, but
 * this generic is duplicated per-module in this port — `menu`/`profile`
 * already have their own copies for the same reason). Unlike those two,
 * {@code review}'s call sites all pass a custom message
 * ({@code "Bạn chỉ có thể update/xóa review/reply của mình"}), so this port
 * keeps the message-overriding constructor Django's class actually has.
 */
public class PermissionDeniedException extends ApiException {

    private static final String DEFAULT_MESSAGE = "You don't have permission to perform this action";

    public PermissionDeniedException() {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", DEFAULT_MESSAGE);
    }

    public PermissionDeniedException(String message) {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", message == null ? DEFAULT_MESSAGE : message);
    }
}
