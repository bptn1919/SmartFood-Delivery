package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors {@code ../backend/users/schemas.py::PasswordNewRequest}. */
public record PasswordNewRequest(
        @NotBlank String resetSessionToken,
        @NotBlank String newPassword,
        @NotBlank String confirmPassword
) {
}
