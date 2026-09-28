package com.amomeal.marketplace.users.dto;

import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;

/**
 * Mirrors {@code ../backend/users/schemas.py::OtpSessionResponse} — a ninja
 * {@code ModelSchema} over {@code UserOTP} excluding
 * {@code user/otp_hash/attempts/otp_verified/active/created_at/updated_at},
 * which leaves exactly these four fields.
 */
public record OtpSessionResponse(
        Long id,
        String resetSessionToken,
        OtpPurpose purpose,
        String targetEmail
) {
    public static OtpSessionResponse from(UserOtp record) {
        return new OtpSessionResponse(record.getId(), record.getResetSessionToken(),
                record.getPurpose(), record.getTargetEmail());
    }
}
