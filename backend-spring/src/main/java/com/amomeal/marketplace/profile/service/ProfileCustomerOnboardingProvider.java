package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import com.amomeal.marketplace.users.service.CustomerOnboardingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real {@link CustomerOnboardingProvider}, now that {@code CustomerProfile}
 * exists. Replaces `users`' {@code NotOnboardedCustomerOnboardingProvider}
 * default via {@code @Primary}. Read-only lookup, mirroring Django's
 * {@code CustomUser.is_onboarded} property exactly
 * ({@code except ObjectDoesNotExist: return False} — no row is created here).
 *
 * <p>PORT-NOTE: Django's {@code Service.verify_otp} (signup OTP verification,
 * `users` module) also does
 * {@code CustomerProfile.objects.get_or_create(user=user)} so every verified
 * signup gets a profile row immediately. That call site lives in `users`
 * source, which is out of this task's scope to modify (`users` is complete
 * and tested; the only sanctioned cross-module touch is calling
 * {@code AuthService.upgradeCustomerToChef}). Net effect: a user who signed up
 * before ever hitting a `profile` endpoint has no {@code CustomerProfile} row
 * until they do (this provider correctly reports {@code false} for them,
 * matching Django's own no-row fallback), and the row is created lazily the
 * first time they touch a `profile` endpoint
 * ({@code CustomerService.getCustomerProfile}'s {@code get_or_create}), same
 * as every other Django ORM method in this module that reads/writes the
 * profile. Flagged in PROGRESS.md.
 */
@Component
@Primary
@RequiredArgsConstructor
public class ProfileCustomerOnboardingProvider implements CustomerOnboardingProvider {

    private final CustomerProfileRepository customerProfileRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isOnboarded(Long userId) {
        return customerProfileRepository.findByUserId(userId)
                .map(com.amomeal.marketplace.profile.entity.CustomerProfile::isOnboarded)
                .orElse(false);
    }
}
