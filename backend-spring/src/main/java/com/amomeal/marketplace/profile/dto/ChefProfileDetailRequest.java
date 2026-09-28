package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;

import java.util.UUID;

/**
 * Mirrors ../../backend/profile/schemas/requests.py::ChefProfileDetailSchema —
 * a {@code ModelSchema} over {@code ChefProfile} excluding
 * {@code id}/{@code user}/{@code rating}/{@code number_of_orders}. Every other
 * model field is present with the model's own default, so every field here is
 * optional; {@code null}/absent means "use the model default" on create and
 * "leave unchanged" on update (Django's {@code exclude_none=True} dict-driven
 * overlay, ported in {@code ProfileService}). Used both by
 * {@code POST /api/chef-profiles/} and as the {@code chef_profile} half of the
 * CUSTOMER→CHEF upgrade payload.
 */
public record ChefProfileDetailRequest(
        UUID avatar,
        String bio,
        String specialty,
        String kitchenAddress,
        String kitchenStreet,
        String kitchenWard,
        String kitchenDistrict,
        String kitchenCity,
        Double kitchenLatitude,
        Double kitchenLongitude,
        Boolean isAcceptingOrders,
        ChefSuspensionLevel suspensionLevel
) {
}
