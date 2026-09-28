package com.amomeal.marketplace.users.service;

import org.springframework.stereotype.Component;

/** Default {@link CustomerOnboardingProvider} — see that interface's javadoc. */
@Component
public class NotOnboardedCustomerOnboardingProvider implements CustomerOnboardingProvider {

    @Override
    public boolean isOnboarded(Long userId) {
        return false;
    }
}
