package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.security.InvalidOrExpiredTokenException;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.exception.InvalidOtpException;
import com.amomeal.marketplace.users.repository.UserOtpRepository;
import com.amomeal.marketplace.users.service.OtpAttemptRecorder;
import com.amomeal.marketplace.users.service.OtpService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The OTP check shared by {@code verify_bank_info_otp} and {@code confirm_withdraw_with_otp}
 * in ../../backend/payment/services.py — a DIFFERENT cascade from {@code users}'
 * {@code verify_otp} (which {@code OtpService#verify} ports), so it is not reused:
 * <ol>
 *   <li>no ACTIVE record for the session token → 401 INVALID_OR_EXPIRED_TOKEN;</li>
 *   <li>already verified → INVALID_OTP "OTP already used" (unreachable: verified records are
 *       also inactive, and the lookup filters {@code active=True});</li>
 *   <li>wrong purpose / another user's record → INVALID_OR_EXPIRED_TOKEN;</li>
 *   <li>expired (2 min for BANK_VERIFY/WITHDRAW_VERIFY) → record burned, INVALID_OR_EXPIRED_TOKEN;</li>
 *   <li>{@code UserOTP.verify(otp)}: attempts already ≥ 3 → Python
 *       {@code ValueError("max_attempts_exceeded")} UNCAUGHT (500); mismatch → attempts + 1,
 *       INVALID_OTP.</li>
 * </ol>
 * The burn and the attempt increment are save-then-raise in Django (autocommit), so they go
 * through {@code users}' {@link OtpAttemptRecorder} ({@code REQUIRES_NEW}) — CLAUDE.md §8b.
 * Consuming the record on success is the caller's job (it happens at a different point in
 * each flow).
 */
@Component
@RequiredArgsConstructor
public class PaymentOtpVerifier {

    private final UserOtpRepository otpRepository;
    private final OtpService otpService;
    private final OtpAttemptRecorder attemptRecorder;
    private final PaymentTx tx;

    public UserOtp verify(CustomUser user, String resetSessionToken, String otp, OtpPurpose purpose) {
        UserOtp record = tx.required(() -> otpRepository.findFirstByResetSessionTokenAndActiveTrue(resetSessionToken))
                .orElseThrow(InvalidOrExpiredTokenException::new);
        if (record.isOtpVerified()) {
            throw new InvalidOtpException("OTP already used");
        }
        if (record.getPurpose() != purpose) {
            throw new InvalidOrExpiredTokenException();
        }
        if (!record.getUserId().equals(user.getId())) {
            throw new InvalidOrExpiredTokenException();
        }
        if (record.isExpired()) {
            attemptRecorder.burn(record.getId());
            throw new InvalidOrExpiredTokenException();
        }
        if (record.getAttempts() >= OtpService.OTP_MAX_ATTEMPTS) {
            throw new IllegalStateException("max_attempts_exceeded"); // Django ValueError, uncaught -> 500
        }
        if (!otpService.matches(record, otp)) {
            attemptRecorder.recordFailedAttempt(record.getId());
            throw new InvalidOtpException();
        }
        return record;
    }

    /** Django {@code record.otp_verified = True; record.active = False; record.save(...)}. */
    public void consume(UserOtp record) {
        tx.required(() -> {
            UserOtp fresh = otpRepository.findById(record.getId()).orElseThrow();
            fresh.setOtpVerified(true);
            fresh.setActive(false);
            otpRepository.saveAndFlush(fresh);
        });
    }
}
