package com.amomeal.marketplace.profile.web;

import com.amomeal.marketplace.profile.dto.ChefProfileDetailRequest;
import com.amomeal.marketplace.profile.dto.ChefProfileDetailResponse;
import com.amomeal.marketplace.profile.dto.ChefProfilePage;
import com.amomeal.marketplace.profile.dto.ChefProfilePublicResponse;
import com.amomeal.marketplace.profile.dto.ChefProfileUpdateRequest;
import com.amomeal.marketplace.profile.dto.CheckChefResponse;
import com.amomeal.marketplace.profile.service.ProfileService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mirrors ../../backend/profile/api.py::ChefProfileController
 * ({@code @api(prefix_or_class="chef-profiles", auth=AuthBear())} — every
 * endpoint in this Django controller requires authentication at the class
 * level and none opt out, so no extra {@code SecurityConfig} entry is needed:
 * Spring's {@code .anyRequest().authenticated()} fallback already covers all
 * of {@code /api/chef-profiles/**}). {@code create_chef_profile}/
 * {@code get_chef_profile_detail} additionally require CHEF
 * ({@code @require_group(CHEF)}); every other endpoint here is reachable by
 * ANY authenticated role, including {@code update_chef_profile} (Django has
 * no decorator on it at all — a CUSTOMER with no chef profile hits 404
 * PROFILE_DOES_NOT_EXIST there, not 401/403).
 */
@RestController
@RequestMapping("/api/chef-profiles")
@RequiredArgsConstructor
public class ChefProfileController {

    private final ProfileService profileService;

    @PostMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public ChefProfileDetailResponse createChefProfile(@AuthenticationPrincipal CustomUser user,
                                                         @Valid @RequestBody ChefProfileDetailRequest payload) {
        return profileService.createChefProfileResponse(user, payload);
    }

    @PatchMapping("/me")
    public ChefProfileDetailResponse updateChefProfile(@AuthenticationPrincipal CustomUser user,
                                                         @Valid @RequestBody ChefProfileUpdateRequest payload) {
        return profileService.updateChefProfile(user, payload);
    }

    @GetMapping("/is-chef-id")
    public CheckChefResponse checkIsChef(@AuthenticationPrincipal CustomUser user) {
        return profileService.checkIsChef(user);
    }

    @GetMapping("/popular")
    public List<ChefProfilePublicResponse> getPopularChefs() {
        return profileService.getPopularChefs();
    }

    @GetMapping("/{chefId}")
    public ChefProfilePublicResponse getChefProfile(@PathVariable Long chefId) {
        return profileService.getChefPublicProfile(chefId);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CHEF')")
    public ChefProfileDetailResponse getChefProfileDetail(@AuthenticationPrincipal CustomUser user) {
        return profileService.getChefProfile(user, user.getId());
    }

    @GetMapping({"", "/"})
    public ChefProfilePage getAllChefProfiles(
            @RequestParam(name = "sort_by", defaultValue = "rating_desc") String sortBy,
            @RequestParam(defaultValue = "1") int page) {
        return profileService.getAllChefProfiles(sortBy, page);
    }
}
