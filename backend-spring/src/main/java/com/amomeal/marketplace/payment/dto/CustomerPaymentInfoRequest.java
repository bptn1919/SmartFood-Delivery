package com.amomeal.marketplace.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Django {@code CustomerPaymentInfoRequest} with its three pydantic {@code field_validator}s
 * ported as normalize-then-validate: the compact constructor applies Django's transformations
 * (strip / remove spaces / remove dashes / collapse whitespace), then the {@link Pattern}s
 * check the result with Django's regexes and messages. Failures surface as the project-wide
 * 401 VALIDATION_ERROR (CLAUDE.md §6).
 */
public record CustomerPaymentInfoRequest(
        @NotNull
        @Pattern(regexp = "^(Vietcombank|VCB|Techcombank|TCB|VPBank|BIDV|Agribank|MBBank|ACB|Sacombank|VietinBank"
                + "|TPBank|HDBank|SHB|OCB|MSB|VIB|LienVietPostBank|SeABank|BacABank|PVcomBank|KienLongBank|NCB)$",
                message = "Input should be a valid VietnamBankEnum value")
        String bankName,
        @NotNull
        @Pattern(regexp = "^\\d{6,8}$", message = "Bank code must be 6-8 digits")
        String bankCode,
        @NotNull
        @Pattern(regexp = "^\\d{6,19}$",
                message = "Account number must be 6-19 digits only (no letters or special characters)")
        String bankAccountNumber,
        @NotNull
        @Pattern(regexp = "^[A-Z\\s]+$",
                message = "Account name must be UPPERCASE letters without accents (A-Z and spaces only)")
        String bankAccountName,
        String bankBranch) {

    public CustomerPaymentInfoRequest {
        if (bankCode != null) {
            bankCode = bankCode.strip().replace(" ", "");                 // v.strip().replace(' ', '')
        }
        if (bankAccountNumber != null) {
            bankAccountNumber = bankAccountNumber.replace(" ", "").replace("-", "");
        }
        if (bankAccountName != null) {
            bankAccountName = bankAccountName.strip().replaceAll("\\s+", " ");
        }
    }
}
