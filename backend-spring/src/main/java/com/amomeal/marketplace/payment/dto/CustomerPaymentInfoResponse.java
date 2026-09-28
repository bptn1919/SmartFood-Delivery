package com.amomeal.marketplace.payment.dto;

/** Django {@code CustomerPaymentInfoResponse} (timestamps as Python {@code isoformat()} strings). */
public record CustomerPaymentInfoResponse(String bankName, String bankCode, String bankAccountNumber,
                                          String bankAccountName, String bankBranch, boolean isVerified,
                                          String verifiedAt, String createdAt, String updatedAt) {
}
