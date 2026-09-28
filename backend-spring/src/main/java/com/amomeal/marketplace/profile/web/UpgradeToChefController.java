package com.amomeal.marketplace.profile.web;

import com.amomeal.marketplace.profile.dto.ChefProfileDetailResponse;
import com.amomeal.marketplace.profile.dto.CustomerToChefRequest;
import com.amomeal.marketplace.profile.service.UpgradeToChefService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mirrors ../../backend/users/api.py::AuthenticateAPI.upgrade_to_chef
 * ({@code POST /api/auth/upgrade-to-chef}, {@code auth=True}). Deliberately
 * lives in `profile`'s {@code web} package rather than `users`' — the request/
 * response schemas are entirely {@code profile}-owned, and `users`'
 * {@code AuthController} is complete/tested and out of scope to touch (see
 * {@code AuthService.upgradeCustomerToChef}'s javadoc, which was written
 * specifically to be called from here). Spring MVC controllers can declare
 * any path regardless of which module they physically live in, so the exact
 * Django path/prefix (`/api/auth`) is preserved without editing
 * `users.web.AuthController`. No extra {@code SecurityConfig} entry is
 * needed: this path isn't in the public/permitAll set, so it falls under the
 * existing {@code .anyRequest().authenticated()} fallback, matching Django's
 * {@code auth=True}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UpgradeToChefController {

    private final UpgradeToChefService upgradeToChefService;

    @PostMapping("/upgrade-to-chef")
    public ChefProfileDetailResponse upgradeToChef(@AuthenticationPrincipal CustomUser user,
                                                     @Valid @RequestBody CustomerToChefRequest payload) {
        return upgradeToChefService.upgradeToChef(user, payload);
    }
}
