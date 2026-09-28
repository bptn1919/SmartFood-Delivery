package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileCustomerOnboardingProvider} — the real
 * {@code CustomerOnboardingProvider} `users` left as a seam
 * ({@code CustomUser.is_onboarded}'s {@code except ObjectDoesNotExist: return False}).
 */
@ExtendWith(MockitoExtension.class)
class ProfileCustomerOnboardingProviderTest {

    @Mock private CustomerProfileRepository customerProfileRepository;

    private ProfileCustomerOnboardingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ProfileCustomerOnboardingProvider(customerProfileRepository);
    }

    @Test
    void noProfileRow_returnsFalse_matchingDjangoObjectDoesNotExistFallback() {
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        assertThat(provider.isOnboarded(1L)).isFalse();
    }

    @Test
    void profileRow_notOnboardedYet_returnsFalse() {
        CustomUser user = CustomUser.builder().id(1L).build();
        CustomerProfile profile = CustomerProfile.builder().user(user).isOnboarded(false).build();
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        assertThat(provider.isOnboarded(1L)).isFalse();
    }

    @Test
    void profileRow_onboarded_returnsTrue() {
        CustomUser user = CustomUser.builder().id(1L).build();
        CustomerProfile profile = CustomerProfile.builder().user(user).isOnboarded(true).build();
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        assertThat(provider.isOnboarded(1L)).isTrue();
    }
}
