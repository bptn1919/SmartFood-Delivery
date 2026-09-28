package com.amomeal.marketplace.profile.service;

/**
 * The {@code is_food_safety_certified} signal `profile`'s own chef-profile
 * responses need
 * (../../backend/profile/schemas/responses.py::resolve_is_food_safety_certified),
 * which reads the {@code certificate} app's table
 * ({@code user.certificate_fk_owner.filter(certificate_type="FOOD_SAFETY", status="APPROVED")}).
 * {@code certificate} is not ported yet (CLAUDE.md §7 orders it after
 * `profile`), so this is a forward seam, same pattern as `dish`'s
 * {@code DishStatsProvider}/{@code DishUserContext}: the default
 * {@link NoCertificateChefCertificationProvider} returns {@code false} for
 * everyone (Django's own behavior on a platform with no approved
 * certificates), and `certificate`'s own port registers a {@code @Primary}
 * bean over it when it lands.
 */
public interface ChefCertificationProvider {

    boolean isFoodSafetyCertified(Long chefUserId);
}
