package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.dto.ChefProfileDetailResponse;
import com.amomeal.marketplace.profile.dto.CustomerToChefRequest;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mirrors ../../backend/users/services.py::Service.upgrade_to_chef — the
 * CUSTOMER→CHEF completion this task exists to wire up. Django's endpoint
 * lives on {@code users/api.py}'s {@code AuthenticateAPI}
 * ({@code POST /api/auth/upgrade-to-chef}), but its request/response schemas
 * are entirely {@code profile}-owned ({@code ChefProfileDetailSchema},
 * {@code ChefPaymentInfoRequest}, {@code ChefProfileDetailResponeSchema}), so
 * `users`' second pass deliberately did NOT build the endpoint — it exposed
 * {@link AuthService#upgradeCustomerToChef(CustomUser)} (the role-transition
 * half: leave CUSTOMER, join CHEF) specifically for this module to call. See
 * that method's javadoc for the full rationale.
 *
 * <p>Call order mirrors Django exactly: check CUSTOMER membership first (fail
 * fast, same as Django's {@code if not user.groups.filter(...): raise
 * Exception(...)} BEFORE the {@code with transaction.atomic():} block) —
 * create the {@code ChefProfile} — create/update the {@code ChefPaymentInfo}
 * — flip the role last. Everything after the pre-check runs in one
 * transaction (this method's own {@code @Transactional}, per CLAUDE.md §8b),
 * matching Django's {@code transaction.atomic()} scope. The
 * "Only CUSTOMER can upgrade to CHEF" guard is a bare Java exception (not an
 * {@code ApiException}), exactly mirroring Django's bare {@code raise
 * Exception(...)} — both fall through to the generic 500
 * CONTACT_ADMIN_FOR_SUPPORT handler, not a purpose-built 4xx.
 */
@Service
@RequiredArgsConstructor
public class UpgradeToChefService {

    private final AuthService authService;
    private final ProfileService profileService;
    private final ChefPaymentService chefPaymentService;

    @Transactional
    public ChefProfileDetailResponse upgradeToChef(CustomUser user, CustomerToChefRequest payload) {
        if (!user.hasRole(UserRole.CUSTOMER)) {
            throw new IllegalStateException("Only CUSTOMER can upgrade to CHEF");
        }

        ChefProfile chefProfile = profileService.createChefProfile(user, payload.chefProfile());
        chefPaymentService.createOrUpdatePaymentInfo(user, payload.chefPayment());
        authService.upgradeCustomerToChef(user);

        return profileService.toDetailResponse(chefProfile);
    }
}
