package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors {@code ../backend/users/schemas.py::PasswordForgetRequest}. */
public record PasswordForgetRequest(@NotBlank String email) {
}
