package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.profile.entity.CustomerAddress;

/** Mirrors ../../backend/profile/schemas/responses.py::CustomerAddressDetailResponse. */
public record CustomerAddressDetailResponse(
        Long id,
        String address,
        String street,
        String ward,
        String district,
        String city,
        Double latitude,
        Double longitude,
        boolean selected,
        boolean deleted,
        String fullAddress
) {
    public static CustomerAddressDetailResponse of(CustomerAddress a) {
        return new CustomerAddressDetailResponse(a.getId(), a.getAddress(), a.getStreet(), a.getWard(),
                a.getDistrict(), a.getCity(), a.getLatitude(), a.getLongitude(), a.isSelected(), a.isDeleted(),
                a.fullAddress());
    }
}
