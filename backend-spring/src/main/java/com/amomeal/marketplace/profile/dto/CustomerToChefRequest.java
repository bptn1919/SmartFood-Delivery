package com.amomeal.marketplace.profile.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Mirrors ../../backend/users/schemas.py::CustomerToChefSchema — the
 * {@code POST /api/auth/upgrade-to-chef} request body. Lives in `profile`
 * (not `users`) because both nested payloads are profile-owned schemas
 * ({@code ChefProfileDetailSchema}, {@code ChefPaymentInfoRequest}); see
 * {@code AuthService.upgradeCustomerToChef}'s javadoc in `users` for why the
 * endpoint itself is built here instead.
 */
public record CustomerToChefRequest(
        @NotNull @Valid ChefProfileDetailRequest chefProfile,
        @NotNull @Valid ChefPaymentInfoRequest chefPayment
) {
}
