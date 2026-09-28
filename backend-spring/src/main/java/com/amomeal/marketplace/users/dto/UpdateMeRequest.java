package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors {@code ../backend/users/schemas.py::UpdateMeSchema}. */
public record UpdateMeRequest(@NotBlank String fullName) {
}
