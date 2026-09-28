package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.security.InvalidOrExpiredTokenException;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.exception.InvalidOtpException;
import com.amomeal.marketplace.users.repository.UserOtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Port of the OTP half of {@code ../backend/users/models.py::UserOTP} +
 * {@code users/queries.py::Query.create_otp/inactive_otp_token} +
 * {@code users/services.py::Service.verify_otp}.
 *
 * <p>Argon2 parameters are Django's <em>exactly</em>:
 * {@code PasswordHasher(time_cost=2, memory_cost=65536, parallelism=1,
 * hash_len=32, salt_len=16)} (users/models.py, with the comment "makes offline
 * enumeration of 10k OTP values slow" — a 4-digit OTP has only 10 000 values,
 * so the hash cost IS the security control here). This is a separate encoder
 * instance from the application {@code PasswordEncoder} bean on purpose: that
 * one uses Spring's OWASP defaults for passwords, these are the OTP-specific
 * costs Django chose.
 *
 * <p>Per CLAUDE.md §8b this service is transactionally self-sufficient — it
 * never relies on a caller-supplied transaction for its own writes.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    /** Django: {@code users/models.py::OTP_MAX_ATTEMPTS}. */
    public static final int OTP_MAX_ATTEMPTS = 3;

    private final UserOtpRepository userOtpRepository;
    private final OtpAttemptRecorder otpAttemptRecorder;

    private final Argon2PasswordEncoder otpHasher = new Argon2PasswordEncoder(16, 32, 1, 65536, 2);
    private final SecureRandom random = new SecureRandom();

    /** The record plus the plaintext OTP, which is emailed and never stored. */
    public record CreatedOtp(UserOtp record, String plainOtp) {
    }

    /** Django: {@code Query.inactive_otp_token} — called before issuing a new OTP. */
    @Transactional
    public int deactivateAllForUser(Long userId) {
        return userOtpRepository.deactivateAllForUser(userId);
    }

    /**
     * Django: {@code Query.create_otp}. A 4-digit code
     * ({@code f"{random.randint(0, 9999):04d}"}) and a
     * {@code secrets.token_urlsafe(32)} session handle.
     */
    @Transactional
    public CreatedOtp create(Long userId, OtpPurpose purpose, String targetEmail) {
        String plainOtp = "%04d".formatted(random.nextInt(10_000));
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String resetSessionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        UserOtp record = UserOtp.builder()
                .userId(userId)
                .otpHash(otpHasher.encode(plainOtp))
                .attempts(0)
                .resetSessionToken(resetSessionToken)
                .purpose(purpose)
                .targetEmail(targetEmail)
                .otpVerified(false)
                .active(true)
                .build();
        return new CreatedOtp(userOtpRepository.save(record), plainOtp);
    }

    /**
     * A bare hash comparison with no attempt/expiry bookkeeping — the
     * email-change flow in {@code Service.verify_email_change} compares the code
     * inline instead of going through {@code UserOTP.verify()}. See
     * {@code AuthService.verifyEmailChange}'s PORT-NOTE.
     */
    public boolean matches(UserOtp record, String otp) {
        return otpHasher.matches(otp, record.getOtpHash());
    }

    /**
     * Django: {@code Service.verify_otp}'s validation cascade, in its exact order
     * and with its exact error mapping. Consumes the record on success
     * (otp_verified=true AND active=false — the OTP is strictly single-use).
     *
     * <p>Order and outcomes:
     * <ol>
     *   <li>no active record for the session token → 401 INVALID_OR_EXPIRED_TOKEN;</li>
     *   <li>already verified → 403 INVALID_OTP "OTP already used" — PORT-NOTE: this
     *       branch is <b>unreachable</b> in Django and here, because verification
     *       also clears {@code active} and the lookup filters {@code active=True};
     *       ported anyway rather than silently dropped;</li>
     *   <li>expired → record deactivated, 401 INVALID_OR_EXPIRED_TOKEN;</li>
     *   <li>attempts already at the cap → record deactivated, 403 INVALID_OTP
     *       "Max OTP attempts exceeded";</li>
     *   <li>wrong code → attempts incremented and persisted, 403 INVALID_OTP.</li>
     * </ol>
     *
     * @return the consumed record (callers branch on {@code purpose} for side effects)
     */
    @Transactional
    public UserOtp verify(String resetSessionToken, String otp) {
        UserOtp record = userOtpRepository.findFirstByResetSessionTokenAndActiveTrue(resetSessionToken)
                .orElseThrow(InvalidOrExpiredTokenException::new);

        if (record.isOtpVerified()) {
            throw new InvalidOtpException("OTP already used");
        }
        // The three failing branches below all write-then-throw. The write goes
        // through OtpAttemptRecorder (REQUIRES_NEW) so the caller's rollback cannot
        // undo it — read that class's javadoc, this was a real bug.
        if (record.isExpired()) {
            otpAttemptRecorder.burn(record.getId());
            record.setActive(false);
            throw new InvalidOrExpiredTokenException();
        }
        if (record.getAttempts() >= OTP_MAX_ATTEMPTS) {
            otpAttemptRecorder.burn(record.getId());
            record.setActive(false);
            throw new InvalidOtpException("Max OTP attempts exceeded");
        }
        // Django: UserOTP.verify() — increments attempts and saves on mismatch.
        if (!otpHasher.matches(otp, record.getOtpHash())) {
            otpAttemptRecorder.recordFailedAttempt(record.getId());
            record.setAttempts(record.getAttempts() + 1);
            throw new InvalidOtpException();
        }

        record.setOtpVerified(true);
        record.setActive(false);
        return userOtpRepository.save(record);
    }
}
