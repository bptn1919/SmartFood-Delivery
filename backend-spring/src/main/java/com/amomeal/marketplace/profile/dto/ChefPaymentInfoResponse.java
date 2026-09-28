package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;

import java.time.Instant;

/** Mirrors ../../backend/profile/schemas/chef_payment.py::ChefPaymentInfoResponse. */
public record ChefPaymentInfoResponse(
        String bankName,
        String bankCode,
        String bankAccountNumber,
        String bankAccountName,
        String bankBranch,
        boolean isVerified,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt
) {
    /** {@code bankAccountNumber} is expected to already be masked by the caller. */
    public static ChefPaymentInfoResponse of(ChefPaymentInfo info, String maskedAccountNumber) {
        return new ChefPaymentInfoResponse(
                info.getBankName(), info.getBankCode(), maskedAccountNumber, info.getBankAccountName(),
                info.getBankBranch(), info.isVerified(), info.getVerifiedAt(), info.getCreatedAt(), info.getUpdatedAt());
    }
}
