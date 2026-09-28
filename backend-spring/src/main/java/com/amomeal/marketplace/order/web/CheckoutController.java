package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.order.dto.ApplyPlatformVoucherRequest;
import com.amomeal.marketplace.order.dto.CheckoutResponse;
import com.amomeal.marketplace.order.dto.PersonalInfoRequest;
import com.amomeal.marketplace.order.dto.UpdateDeliveryTypesRequest;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.service.OrderService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/api.py::CheckoutController ({@code prefix
 * "checkouts"}, {@code auth=AuthBear()}, no group gate). Post-port fix: every
 * edit/place/apply endpoint requires the caller to own the checkout (Django never checked).
 *
 * <p>{@code payment-method} and {@code delivery-time} take a BARE JSON value as
 * the body ({@code "PAYOS"}, {@code "12:30:00"}), exactly like ninja's
 * {@code payload: PaymentMethodEnum} / {@code payload: time}.
 */
@RestController
@RequestMapping("/api/checkouts")
@RequiredArgsConstructor
public class CheckoutController {

    private final OrderService orderService;

    /** Django: {@code POST /api/checkouts/} — selected cart items -&gt; DRAFT orders. */
    @PostMapping({"", "/"})
    public CheckoutResponse checkout(@AuthenticationPrincipal CustomUser user) {
        return orderService.checkout(user);
    }

    @PatchMapping("/{uid}/profile")
    public CheckoutResponse editProfile(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                        @Valid @RequestBody PersonalInfoRequest payload) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.editProfileOfCheckout(uid, payload);
    }

    @PatchMapping("/{uid}/payment-method")
    public CheckoutResponse editPaymentMethod(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                              @RequestBody PaymentMethod payload) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.editPaymentMethodOfCheckout(uid, payload);
    }

    @PatchMapping("/{uid}/delivery-time")
    public CheckoutResponse editDeliveryTime(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                             @RequestBody LocalTime payload) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.editDeliveryTimeOfCheckout(uid, payload);
    }

    @PatchMapping("/{uid}/delivery-address/{addressId}")
    public CheckoutResponse editDeliveryAddress(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                                @PathVariable Long addressId) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.editDeliverAddressOfCheckout(user, uid, addressId);
    }

    @PatchMapping("/{uid}/delivery-types")
    public CheckoutResponse editDeliveryTypes(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                              @Valid @RequestBody UpdateDeliveryTypesRequest payload) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.editDeliveryTypesOfCheckout(uid, payload);
    }

    /**
     * Django: {@code POST /api/checkouts/{uid}/place-order?bank_code=...}. The COD
     * "new order" Firebase push to each chef that follows in Django is dropped with
     * mongo_chat.
     */
    @PostMapping("/{uid}/place-order")
    public CheckoutResponse placeOrder(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                       @RequestParam(name = "bank_code", required = false) String bankCode) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.placeOrder(uid, bankCode);
    }

    /** Django: {@code POST /api/checkouts/{uid}/apply-platform-voucher}. */
    @PostMapping("/{uid}/apply-platform-voucher")
    public CheckoutResponse applyPlatformVoucher(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                                 @Valid @RequestBody ApplyPlatformVoucherRequest payload) {
        orderService.assertOwnsCheckout(uid, user);
        return orderService.applyPlatformVoucherToCheckout(user, uid, payload);
    }
}
