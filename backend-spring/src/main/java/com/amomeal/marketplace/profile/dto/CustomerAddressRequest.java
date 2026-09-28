package com.amomeal.marketplace.profile.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Mirrors ../../backend/profile/schemas/requests.py::CustomerAddressRequest —
 * a {@code ModelSchema} over {@code CustomerAddress} excluding only
 * {@code user}, so {@code selected}/{@code deleted} are technically
 * settable directly on create too (ported as-is; Django doesn't guard
 * against it either).
 */
public record CustomerAddressRequest(
        @NotNull String address,
        @NotNull String street,
        @NotNull String ward,
        @NotNull String district,
        @NotNull String city,
        Double latitude,
        Double longitude,
        Boolean selected,
        Boolean deleted
) {
}
