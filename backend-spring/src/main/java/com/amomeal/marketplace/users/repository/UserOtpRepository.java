package com.amomeal.marketplace.users.repository;

import com.amomeal.marketplace.users.entity.UserOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserOtpRepository extends JpaRepository<UserOtp, Long> {

    /**
     * Django: {@code Query.get_otp_record} —
     * {@code UserOTP.objects.filter(reset_session_token=..., active=True).first()}.
     * Used for the SIGNUP-verify path, where the record is genuinely still active
     * at lookup time.
     */
    Optional<UserOtp> findFirstByResetSessionTokenAndActiveTrue(String resetSessionToken);

    /**
     * Same lookup WITHOUT the {@code active=True} filter — deliberately does not
     * mirror Django, which dead-ends {@code reset_password} by requiring
     * {@code active=True} on a record that {@code verify_otp} always deactivates
     * in the same request. Fixed here per the user's explicit decision (see
     * AuthService.resetPassword's javadoc and PROGRESS.md); {@code otpVerified}
     * still gates it.
     */
    Optional<UserOtp> findFirstByResetSessionToken(String resetSessionToken);

    /**
     * Increments the failed-attempt counter for one record. Written as a bulk
     * UPDATE (rather than a dirty-checked entity save) so it can be committed on
     * its own by {@link com.amomeal.marketplace.users.service.OtpAttemptRecorder}
     * even though the caller is about to throw — see that class's javadoc.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserOtp o set o.attempts = o.attempts + 1, o.updatedAt = CURRENT_TIMESTAMP where o.id = :id")
    int incrementAttempts(@Param("id") Long id);

    /** Burns one record (expiry / attempt-cap), likewise committed independently. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserOtp o set o.active = false, o.updatedAt = CURRENT_TIMESTAMP where o.id = :id")
    int deactivate(@Param("id") Long id);

    /** Django: {@code Query.inactive_otp_token} — bulk-deactivate a user's live OTPs. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserOtp o set o.active = false, o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.userId = :userId and o.active = true")
    int deactivateAllForUser(@Param("userId") Long userId);
}
