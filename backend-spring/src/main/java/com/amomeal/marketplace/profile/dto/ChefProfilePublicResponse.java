package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;

/**
 * Mirrors ../../backend/profile/schemas/responses.py::ChefProfilePublicResponseSchema
 * (no bank info — that's {@code ChefProfileDetailResponse}-only).
 */
public record ChefProfilePublicResponse(
        int userId,
        String fullname,
        String avatar,
        String phone,
        String mail,
        boolean isFoodSafetyCertified,
        String bio,
        String specialty,
        String kitchenAddress,
        String kitchenStreet,
        String kitchenWard,
        String kitchenDistrict,
        String kitchenCity,
        Double kitchenLatitude,
        Double kitchenLongitude,
        double rating,
        int numberOfOrders,
        boolean isAcceptingOrders,
        ChefSuspensionLevel suspensionLevel
) {
    public static ChefProfilePublicResponse of(ChefProfile p, boolean isFoodSafetyCertified) {
        var user = p.getUser();
        String fullName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return new ChefProfilePublicResponse(
                user.getId().intValue(),
                fullName,
                p.getAvatar() == null ? null : p.getAvatar().getPublicUrl(),
                user.getPhoneNumber(),
                user.getEmail(),
                isFoodSafetyCertified,
                p.getBio(),
                p.getSpecialty(),
                p.getKitchenAddress(),
                p.getKitchenStreet(),
                p.getKitchenWard(),
                p.getKitchenDistrict(),
                p.getKitchenCity(),
                p.getKitchenLatitude(),
                p.getKitchenLongitude(),
                p.getRating(),
                p.getNumberOfOrders(),
                p.isAcceptingOrders(),
                p.getSuspensionLevel());
    }
}
