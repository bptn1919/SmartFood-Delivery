package com.amomeal.marketplace.menu.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Mirrors ../../backend/menu/schemas/requests.py::AddDishToMenuSchema
 * ({@code menu_uid} is a path parameter, not part of the body).
 */
public record AddDishToMenuRequest(
        @NotNull UUID dishUid,
        Integer position,
        Boolean active
) {
    public int positionOrDefault() {
        return position == null ? 0 : position;
    }

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
