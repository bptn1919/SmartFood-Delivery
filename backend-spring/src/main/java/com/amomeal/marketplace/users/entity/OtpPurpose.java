package com.amomeal.marketplace.users.entity;

/**
 * Mirrors {@code ../backend/utils/enums.py::OtpPurposeEnum}.
 *
 * <p>{@code expiryMinutes} mirrors {@code marketplace/settings.py::OTP_EXPIRY_MINUTES}
 * (a dict keyed by purpose, defaulting to
 * {@code RESET_PASSWORD_EXPIRES_IN_MINUTES} / 15 for a purpose that isn't listed
 * — which is exactly the case for EMAIL_CHANGE, see
 * {@code users/models.py::_otp_expiry_minutes}).
 */
public enum OtpPurpose {

    SIGNUP(15),
    RESET_PASSWORD(15),
    /** Not present in OTP_EXPIRY_MINUTES — falls through to the 15-minute default. */
    EMAIL_CHANGE(15),
    BANK_VERIFY(2),
    WITHDRAW_VERIFY(2);

    private final int expiryMinutes;

    OtpPurpose(int expiryMinutes) {
        this.expiryMinutes = expiryMinutes;
    }

    public int expiryMinutes() {
        return expiryMinutes;
    }
}
