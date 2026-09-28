package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Mirrors {@code ../backend/users/schemas.py::EmailChangeRequest}. */
public record EmailChangeRequest(@NotBlank @Email String newEmail) {
}
