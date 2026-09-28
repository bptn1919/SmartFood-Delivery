package com.amomeal.marketplace.payment.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Django-ninja's {@code ninja.errors.HttpError(status, msg)} as raised by
 * ../../backend/payment/services.py ({@code request_withdraw_otp}'s six 400s,
 * {@code verify_bank_info_otp}'s "Bank info not found") and payment/api.py (the chef-balance
 * 404 "Chef not found or not a chef"). Rendered by Django's {@code _default_http_error} as
 * {@code message_code="HTTP_ERROR"}, {@code message="Http error"}, the text in {@code data} —
 * the same shape {@code order}'s {@code OrderHttpException} uses.
 *
 * <p>Payment has no {@code exceptions/payment.py} in Django: every other failure is either a
 * {@code users} exception ({@code InvalidOrExpiredToken}, {@code InvalidOtp}), a Python
 * {@code ValueError} (→ 500, see {@code PaymentValueError}), or a result dict.
 */
public class PaymentHttpException extends ApiException {

    public PaymentHttpException(HttpStatus status, String detailMessage) {
        super(status, "HTTP_ERROR", "Http error", detailMessage);
    }
}
