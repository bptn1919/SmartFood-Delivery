package com.amomeal.marketplace.menu.dto;

import com.amomeal.marketplace.menu.entity.MenuStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Mirrors ../../backend/menu/schemas/requests.py::MenuSchema — used for both
 * create ({@code POST /api/menus}) and update ({@code PUT /api/menus/{uid}}),
 * exactly like Django reuses one schema for both. {@code name} is required
 * (Django {@code CharField()}, no {@code blank=True}); {@code description} is
 * optional ({@code TextField(blank=True, null=True)}); {@code status} is
 * required because the schema re-declares it with no default, overriding the
 * model's own {@code default=ACTIVE}.
 */
public record MenuRequest(
        @NotBlank String name,
        String description,
        @NotNull MenuStatus status
) {
}
