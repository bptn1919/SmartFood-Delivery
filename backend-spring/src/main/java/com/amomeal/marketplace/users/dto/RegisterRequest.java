package com.amomeal.marketplace.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * TODO (full users module pass): Django's AUTH_PASSWORD_VALIDATORS also runs
 * UserAttributeSimilarityValidator/CommonPasswordValidator/
 * NumericPasswordValidator — only the minimum-length rule is ported here so
 * far. Port the rest faithfully before treating registration as complete.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 150) String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8) String password,
        @Size(max = 15) String phoneNumber
) {
}
