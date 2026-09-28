package com.amomeal.marketplace.profile.dto;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.profile.entity.DietLevel;
import com.amomeal.marketplace.profile.entity.DietMode;

import java.util.UUID;

/**
 * Mirrors ../../backend/profile/schemas/requests.py::CustomerProfileUpdateSchema.
 *
 * <p>PORT-NOTE (real Django quirk, preserved): {@code phone} is accepted here
 * but {@code CustomerProfile} has no such field — Django's
 * {@code setattr(profile, "phone", value)} silently sets a throwaway Python
 * attribute that {@code profile.save()} never persists (not a mapped model
 * field). This port accepts and just as silently ignores it too, rather than
 * "fixing" it into updating {@code CustomUser.phone_number} (a different,
 * more useful behavior Django never actually implements).
 */
public record CustomerProfileUpdateRequest(
        String bio,
        UUID attachmentUid,
        String phone,
        DietMode dietMode,
        DietLevel dietLevel,
        AllergyMode allergyMode
) {
}
