package com.amomeal.marketplace.profile.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Mirrors ../../backend/profile/schemas/chef_payment.py::ChefPaymentInfoRequest.
 * Field-level {@code @NotBlank} only checks presence; the real format
 * validation (bank name membership, bank code/account number/account name/
 * citizen id/tax code regexes, with the same normalize-then-validate order
 * Django's Pydantic {@code field_validator}s use) happens in
 * {@link com.amomeal.marketplace.profile.service.ChefPaymentService}, raising
 * {@link com.amomeal.marketplace.profile.exception.ChefPaymentValidationException}
 * to match Django's 401 VALIDATION_ERROR quirk (CLAUDE.md §6) — see that
 * exception's javadoc for why format validation isn't done via {@code @Pattern}.
 */
public record ChefPaymentInfoRequest(
        @NotBlank String bankName,
        @NotBlank String bankCode,
        @NotBlank String bankAccountNumber,
        @NotBlank String bankAccountName,
        String bankBranch,
        String citizenId,
        String taxCode
) {
}
