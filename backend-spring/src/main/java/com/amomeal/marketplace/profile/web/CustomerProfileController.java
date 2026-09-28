package com.amomeal.marketplace.profile.web;

import com.amomeal.marketplace.dish.dto.DishResponse;
import com.amomeal.marketplace.profile.dto.CustomerAddressDetailResponse;
import com.amomeal.marketplace.profile.dto.CustomerAddressRequest;
import com.amomeal.marketplace.profile.dto.CustomerAddressResponse;
import com.amomeal.marketplace.profile.dto.CustomerFullProfileResponse;
import com.amomeal.marketplace.profile.dto.CustomerOnboardingRequest;
import com.amomeal.marketplace.profile.dto.CustomerProfileUpdateRequest;
import com.amomeal.marketplace.profile.dto.SetDefaultAddressResponse;
import com.amomeal.marketplace.profile.service.CustomerService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/profile/api.py::CustomerProfileController
 * ({@code @api(prefix_or_class="customer-profiles", auth=AuthBear())} — every
 * endpoint requires authentication only; Django's {@code @require_group(CUSTOMER)}
 * decorators are commented out on every method that has one, so no role
 * gating is applied here either, matching that exactly).
 */
@RestController
@RequestMapping("/api/customer-profiles")
@RequiredArgsConstructor
public class CustomerProfileController {

    private final CustomerService customerService;

    @GetMapping({"", "/"})
    public CustomerFullProfileResponse getCustomerProfile(@AuthenticationPrincipal CustomUser user) {
        return customerService.getCustomerProfile(user);
    }

    @PatchMapping({"", "/"})
    public CustomerFullProfileResponse updateCustomerProfile(@AuthenticationPrincipal CustomUser user,
                                                               @Valid @RequestBody CustomerProfileUpdateRequest payload) {
        return customerService.updateCustomerProfile(user, payload);
    }

    @PostMapping("/onboard")
    public CustomerFullProfileResponse onboardCustomerProfile(@AuthenticationPrincipal CustomUser user,
                                                                @Valid @RequestBody CustomerOnboardingRequest payload) {
        return customerService.onboardCustomerProfile(user, payload);
    }

    @PatchMapping("/addresses/{addressId}/delete")
    public boolean softDeleteCustomerAddress(@AuthenticationPrincipal CustomUser user, @PathVariable Long addressId) {
        return customerService.softDeleteCustomerAddress(user, addressId);
    }

    @GetMapping("/addresses")
    public List<CustomerAddressResponse> getCustomerAddress(@AuthenticationPrincipal CustomUser user) {
        return customerService.getCustomerAddress(user);
    }

    @GetMapping("/addresses/get-one")
    public CustomerAddressResponse getOneCustomerAddress(@AuthenticationPrincipal CustomUser user) {
        return customerService.getOneCustomerAddress(user);
    }

    @PostMapping("/addresses")
    public CustomerAddressDetailResponse createNewCustomerAddress(@AuthenticationPrincipal CustomUser user,
                                                                     @Valid @RequestBody CustomerAddressRequest payload) {
        return customerService.createNewCustomerAddress(user, payload);
    }

    @GetMapping("/favorite-dishes")
    public List<DishResponse> getFavoriteDishes(@AuthenticationPrincipal CustomUser user) {
        return customerService.getFavoriteDishes(user);
    }

    @PostMapping("/favorite-dish/{dishUid}")
    public DishResponse addFavoriteDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID dishUid) {
        return customerService.addFavoriteDish(user, dishUid);
    }

    @PatchMapping("/favorite-dish/{dishUid}")
    public boolean removeFavoriteDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID dishUid) {
        return customerService.removeFavoriteDish(user, dishUid);
    }

    @GetMapping("/addresses/{addressId}")
    public CustomerAddressDetailResponse getOneCustomerAddressById(@AuthenticationPrincipal CustomUser user,
                                                                      @PathVariable Long addressId) {
        return customerService.getOneCustomerAddressById(user, addressId);
    }

    @PutMapping("/addresses/{addressId}/set-default")
    public SetDefaultAddressResponse setDefaultAddress(@AuthenticationPrincipal CustomUser user, @PathVariable Long addressId) {
        return customerService.setDefaultCustomerAddress(user, addressId);
    }
}
