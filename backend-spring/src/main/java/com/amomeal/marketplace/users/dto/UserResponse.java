package com.amomeal.marketplace.users.dto;

import com.amomeal.marketplace.users.entity.CustomUser;

/**
 * Mirrors {@code ../backend/users/schemas.py::UserSchema} —
 * {@code {username, email, is_onboarded}} and nothing else. {@code is_onboarded}
 * is a {@code CustomUser} property backed by
 * {@code self.customer_profile.is_onboarded} (profile module), supplied here by
 * the {@link com.amomeal.marketplace.users.service.CustomerOnboardingProvider}
 * seam.
 */
public record UserResponse(String username, String email, boolean isOnboarded) {

    public static UserResponse of(CustomUser user, boolean isOnboarded) {
        return new UserResponse(user.getUsername(), user.getEmail(), isOnboarded);
    }
}
