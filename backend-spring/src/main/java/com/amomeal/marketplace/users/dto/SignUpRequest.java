package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Mirrors {@code ../backend/users/schemas.py::SignUpSchema} field for field
 * ({@code firstname}/{@code lastname} really are one word in Django, not
 * {@code first_name}). {@code password_confirm} is declared by Django's schema
 * but — PORT-NOTE — is never actually compared to {@code password} anywhere in
 * {@code Service.signup}; that check exists only on the password-RESET path
 * ({@code ConfirmPasswordNotMatch}). Preserved as-is (CLAUDE.md §0.1).
 */
public record SignUpRequest(
        @NotBlank String firstname,
        @NotBlank String lastname,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank String password,
        String passwordConfirm,
        @Size(max = 15) String phoneNumber
) {
}
