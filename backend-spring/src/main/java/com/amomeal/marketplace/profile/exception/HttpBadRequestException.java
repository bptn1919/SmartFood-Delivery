package com.amomeal.marketplace.profile.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors django-ninja's generic {@code ninja.errors.HttpError(400, msg)},
 * used directly (not one of the module's own {@code exceptions/profiles.py}
 * classes) at two call sites in ../../backend/profile/services/__init__.py:
 * {@code onboard_customer_profile} (invalid ingredient uid) and
 * {@code update_customer_profile} (diet_mode/diet_level combination). Django's
 * {@code utils/router/exception.py::_default_http_error} handler formats these
 * as {@code message_code="HTTP_ERROR"}, {@code message="Http error"}, the
 * exception's own text carried in {@code data} — ported verbatim rather than
 * given a purpose-specific message_code, since that's genuinely what Django
 * sends over the wire for these two call sites.
 */
public class HttpBadRequestException extends ApiException {

    public HttpBadRequestException(String detailMessage) {
        super(HttpStatus.BAD_REQUEST, "HTTP_ERROR", "Http error", detailMessage);
    }
}
