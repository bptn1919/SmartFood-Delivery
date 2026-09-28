package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Mirrors {@code ../backend/users/schemas.py::PasswordChangeRequest}, including
 * its {@code Field(..., min_length=8)} on the new password only.
 */
public record PasswordChangeRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 8) String newPassword
) {
}
