package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors {@code ../backend/users/schemas.py::PasswordVerifyOtpRequest}. */
public record VerifyOtpRequest(
        @NotBlank String resetSessionToken,
        @NotBlank String otp
) {
}
