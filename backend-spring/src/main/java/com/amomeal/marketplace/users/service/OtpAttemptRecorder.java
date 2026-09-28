package com.amomeal.marketplace.users.service;

import com.amomeal.marketplace.users.repository.UserOtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OTP writes that must survive the exception thrown immediately after them.
 *
 * <p><b>Why this exists (a real bug caught by testing, related to CLAUDE.md
 * §8b).</b> Django increments {@code attempts} with
 * {@code record.save(update_fields=[...])} and only THEN raises — under
 * autocommit the counter is durable, which is the whole point of a 3-attempt
 * lockout. Ported naively into Spring the same two lines are useless: the save
 * joins the caller's transaction ({@code AuthService.verifyOtp} →
 * {@code OtpService.verify}, both {@code @Transactional}) and the
 * {@code InvalidOtpException} rolls it straight back, so an attacker gets
 * unlimited guesses at a 4-digit code. The first version of
 * {@code AuthOtpControllerTest.verifyOtp_wrongCodeThreeTimes_locksTheSessionOut}
 * failed on exactly that (4th attempt returned 200, not a lockout).
 *
 * <p>{@code REQUIRES_NEW} on a separate bean is what makes it durable: a real
 * suspended-outer/new-inner transaction that commits before the caller unwinds.
 * It has to be a separate bean — a {@code @Transactional} method called from
 * within the same class is self-invocation and never goes through the proxy.
 */
@Component
@RequiredArgsConstructor
public class OtpAttemptRecorder {

    private final UserOtpRepository userOtpRepository;

    /** Commits {@code attempts += 1} for this record, independently of the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(Long otpId) {
        userOtpRepository.incrementAttempts(otpId);
    }

    /** Commits {@code active = false} for this record, independently of the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void burn(Long otpId) {
        userOtpRepository.deactivate(otpId);
    }
}
