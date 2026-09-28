package com.amomeal.marketplace.profile.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Mirrors ../../backend/profile/schemas/requests.py::CustomerOnboardingSchema. */
public record CustomerOnboardingRequest(
        @NotNull Double heightCm,
        @NotNull Double weightKg,
        @NotNull List<UUID> allergicIngredientUids,
        @NotNull List<UUID> favoriteIngredientUids
) {
}
