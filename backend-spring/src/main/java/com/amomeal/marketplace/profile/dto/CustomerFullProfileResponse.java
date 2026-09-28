package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.profile.entity.DietLevel;
import com.amomeal.marketplace.profile.entity.DietMode;

import java.util.List;

/** Mirrors ../../backend/profile/schemas/responses.py::CustomerFullProfileSchema. */
public record CustomerFullProfileResponse(
        String bio,
        int points,
        boolean isOnboarded,
        DietMode dietMode,
        DietLevel dietLevel,
        AllergyMode allergyMode,
        String avatar,
        List<CustomerAddressDetailResponse> addresses,
        String fullname,
        String mail,
        String phone
) {
    public static CustomerFullProfileResponse of(CustomerProfile p, List<CustomerAddressDetailResponse> addresses) {
        var user = p.getUser();
        String fullName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return new CustomerFullProfileResponse(
                p.getBio(), p.getPoints(), p.isOnboarded(), p.getDietMode(), p.getDietLevel(), p.getAllergyMode(),
                p.getAvatar() == null ? null : p.getAvatar().getPublicUrl(),
                addresses, fullName, user.getEmail(), user.getPhoneNumber());
    }
}
