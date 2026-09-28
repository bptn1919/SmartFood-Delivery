package com.amomeal.marketplace.profile.dto;

import java.util.UUID;

/** Mirrors ../../backend/profile/schemas/requests.py::ChefProfileUpdateSchema. */
public record ChefProfileUpdateRequest(
        String bio,
        String specialty,
        UUID attachmentUid
) {
}
