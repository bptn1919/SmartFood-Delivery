package com.amomeal.marketplace.profile.web;

import com.amomeal.marketplace.profile.dto.ChefPaymentInfoRequest;
import com.amomeal.marketplace.profile.dto.ChefPaymentInfoResponse;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.service.ChefPaymentService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Mirrors ../../backend/profile/api.py's {@code ChefPaymentController}
 * (prefix {@code "chef-payment"}, registered via
 * {@code BaseAPI.auto_discover_controllers()} — this is the LIVE router).
 *
 * <p>PORT-NOTE (real Django bugs, investigated, not silently "fixed"):
 * <ol>
 *   <li>{@code profile/api.py} defines TWO classes both literally named
 *       {@code ChefPaymentController}, decorated identically
 *       ({@code @api(prefix_or_class="chef-payment", ...)}) — a copy-paste
 *       duplicate, not two different prefixes. The second definition drops
 *       {@code @require_group(CHEF)} from {@code get_payment_info} and uses
 *       HTTP DELETE instead of PATCH for {@code delete_payment_info}. Both
 *       classes get decorated (django-ninja-extra's {@code api_controller}
 *       registers on decoration, independent of the Python name binding), but
 *       Django's URL resolver matches the FIRST-registered pattern for an
 *       identical path, so the SECOND definition's routes are dead/
 *       unreachable — this port implements the FIRST (CHEF-gated GET, PATCH
 *       delete), which is also consistent with every sibling endpoint in this
 *       file requiring CHEF.</li>
 *   <li>{@code profile/api_chef_payment.py} defines a wholly separate,
 *       OTP-gated bank-verification router (save unverified → email OTP →
 *       verify → {@code is_verified=true}) that LOOKS live (imported into
 *       {@code marketplace/urls.py}) but its mount line is commented out:
 *       {@code #api.add_router("/chef", chef_payment_router)}. It is dead
 *       code — genuinely unreachable from any HTTP client — so it is
 *       deliberately NOT ported as a controller (its service methods'
 *       intent is preserved as PORT-NOTEs in {@code ChefPaymentService} in
 *       case a future session wants to actually wire it up).</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/chef-payment")
@RequiredArgsConstructor
public class ChefPaymentController {

    private final ChefPaymentService chefPaymentService;

    @PostMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public ChefPaymentInfoResponse createOrUpdatePaymentInfo(@AuthenticationPrincipal CustomUser user,
                                                               @Valid @RequestBody ChefPaymentInfoRequest payload) {
        ChefPaymentInfo info = chefPaymentService.createOrUpdatePaymentInfo(user, payload);
        return chefPaymentService.toMaskedResponse(info);
    }

    @GetMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public ChefPaymentInfoResponse getPaymentInfo(@AuthenticationPrincipal CustomUser user) {
        ChefPaymentInfo info = chefPaymentService.getPaymentInfo(user);
        return chefPaymentService.toMaskedResponse(info);
    }

    @PatchMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public java.util.Map<String, Object> deletePaymentInfo(@AuthenticationPrincipal CustomUser user) {
        chefPaymentService.deletePaymentInfo(user);
        return java.util.Map.of("success", true, "message", "Payment information deleted");
    }
}
