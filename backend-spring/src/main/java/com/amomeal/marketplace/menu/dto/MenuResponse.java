package com.amomeal.marketplace.menu.dto;

import com.amomeal.marketplace.menu.entity.Menu;
import com.amomeal.marketplace.menu.entity.MenuStatus;

import java.util.UUID;

/**
 * Mirrors ../../backend/menu/schemas/responses.py::MenuResponse (Meta excludes
 * only created_at/deleted/updated_at/updater — uid/name/description/status/chef
 * remain). {@code chef} is Django Ninja's default FK representation: the
 * related row's own primary key (an int, Django's default {@code User.id}),
 * not a nested object.
 */
public record MenuResponse(
        UUID uid,
        String name,
        String description,
        MenuStatus status,
        Long chef
) {
    public static MenuResponse of(Menu menu) {
        return new MenuResponse(
                menu.getUid(),
                menu.getName(),
                menu.getDescription(),
                menu.getStatus(),
                menu.getChef() == null ? null : menu.getChef().getId());
    }
}
