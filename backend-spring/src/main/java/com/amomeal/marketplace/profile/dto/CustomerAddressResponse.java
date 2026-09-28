package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.profile.entity.CustomerAddress;

/**
 * Mirrors ../../backend/profile/schemas/responses.py::CustomerAddressResponse
 * — the LIST-view shape, which is deliberately narrower than
 * {@link CustomerAddressDetailResponse}: Django's {@code Meta.exclude} drops
 * {@code address}/{@code street}/{@code ward}/{@code district}/{@code city}
 * here (only {@code full_address} carries that information), a real
 * difference from the detail response preserved as-is.
 */
public record CustomerAddressResponse(
        Long id,
        Double latitude,
        Double longitude,
        boolean selected,
        boolean deleted,
        String fullAddress
) {
    public static CustomerAddressResponse of(CustomerAddress a) {
        return new CustomerAddressResponse(a.getId(), a.getLatitude(), a.getLongitude(), a.isSelected(),
                a.isDeleted(), a.fullAddress());
    }
}
