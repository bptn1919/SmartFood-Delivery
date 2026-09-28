package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.dto.CheckoutResponse;
import com.amomeal.marketplace.order.dto.OrderItemResponse;
import com.amomeal.marketplace.order.dto.OrderResponse;
import com.amomeal.marketplace.order.dto.OrderResponseWithInfo;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.repository.OrderAppliedVoucherRepository;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Port of ../../backend/order/orm/order.py::OrderMapper.
 *
 * <h2>Django bugs preserved verbatim (PORT-NOTE'd, regression-tested)</h2>
 * <ol>
 *   <li><b>{@code to_response_with_info} crashes for a chef with no
 *       {@code ChefProfile}.</b> {@code chef_lat}/{@code chef_lng} are only
 *       assigned inside {@code if hasattr(order.chef, 'chef_profile'):}; the
 *       {@code else} branch sets {@code chef_address} only, so building the
 *       response then raises {@code UnboundLocalError} — an uncaught 500. In
 *       practice every real chef has a profile (created by upgrade-to-chef), but
 *       an order whose chef lacks one cannot be read, confirmed, cancelled, etc.
 *       through any endpoint returning this shape. Ported as an uncaught
 *       {@link IllegalStateException} (same 500 CONTACT_ADMIN_FOR_SUPPORT).</li>
 *   <li><b>{@code chef_name} is the username, not the full name, in the "with
 *       info" shape.</b> Django does
 *       {@code getattr(order.chef, 'full_name', getattr(order.chef, 'username', ...))};
 *       {@code CustomUser} has no {@code full_name} attribute (only the
 *       {@code get_full_name()} method), so it always falls through to
 *       {@code username}. The nested {@link OrderResponse} uses
 *       {@code get_full_name()} — the two shapes disagree, preserved.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class OrderMapper {

    static final List<VoucherReservationStatus> ACTIVE_VOUCHER_STATUSES =
            List.of(VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED);

    private final OrderAppliedVoucherRepository appliedVoucherRepository;
    private final ChefProfileRepository chefProfileRepository;
    private final OrderPaymentGateway paymentGateway;

    /** Django: {@code AbstractUser.get_full_name()} — {@code "first last".strip()}. */
    public static String fullName(CustomUser user) {
        if (user == null) {
            return "";
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        return (first + " " + last).strip();
    }

    private String voucherCodes(Order order) {
        List<AppliedVoucher> applied = appliedVoucherRepository.findByOrderUidAndStatusIn(order.getUid(), ACTIVE_VOUCHER_STATUSES);
        if (applied.isEmpty()) {
            return null;
        }
        return applied.stream().map(a -> a.getVoucher().getCode()).collect(Collectors.joining(", "));
    }

    private static String imageUrl(Dish dish) {
        return dish != null && dish.getAttachment() != null ? dish.getAttachment().getPublicUrl() : null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Django: {@code OrderMapper.to_response}. */
    public OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            items.add(new OrderItemResponse(
                    item.getDish() != null ? item.getDish().getUid() : null,
                    item.getDishName(),
                    null,
                    imageUrl(item.getDish()),
                    item.getQuantity(),
                    item.getPrice(),
                    item.subtotal()));
        }
        // PORT-NOTE: Django dereferences order.chef.id unguarded (AttributeError on a null chef).
        return new OrderResponse(
                order.getUid(),
                order.getChef().getId(),
                fullName(order.getChef()),
                order.getSubTotal(),
                order.getTaxAndFees(),
                order.getDeliveryFee(),
                nz(order.getPlatformSubtotalDiscount()),
                nz(order.getPlatformShippingDiscount()),
                nz(order.getShopDiscount()),
                nz(order.getTotalDiscount()),
                order.getTotalPrice(),
                voucherCodes(order),
                items);
    }

    /**
     * Django: {@code OrderMapper.to_checkout_response}. Checkout-level
     * sub_total/tax/delivery/total/total_discount come from the stored Checkout row;
     * the two platform discount figures are summed across the orders.
     */
    public CheckoutResponse toCheckoutResponse(List<Order> orders) {
        if (orders.isEmpty()) {
            // Django: raise ValueError("No orders provided") -> uncaught 500
            throw new IllegalStateException("No orders provided");
        }
        Checkout checkout = orders.get(0).getCheckout();
        BigDecimal platformSubtotal = BigDecimal.ZERO;
        BigDecimal platformShipping = BigDecimal.ZERO;
        List<OrderResponse> orderResponses = new ArrayList<>();
        for (Order order : orders) {
            platformSubtotal = platformSubtotal.add(nz(order.getPlatformSubtotalDiscount()));
            platformShipping = platformShipping.add(nz(order.getPlatformShippingDiscount()));
            orderResponses.add(toResponse(order));
        }
        return new CheckoutResponse(
                checkout.getUid(),
                checkout.getFullName(),
                checkout.getPhoneNumber(),
                checkout.getDeliveryDate(),
                checkout.getDeliveryTime(),
                checkout.getDeliveryAddress() != null ? checkout.getDeliveryAddress().fullAddress() : null,
                checkout.getPaymentMethod(),
                checkout.getSubTotal(),
                checkout.getTaxAndFees(),
                checkout.getDeliveryFee(),
                platformSubtotal,
                platformShipping,
                nz(checkout.getTotalDiscount()),
                checkout.getTotalPrice(),
                orderResponses,
                null, null, null, null);
    }

    /** Django: {@code OrderMapper.to_response_with_info}. See class javadoc for the preserved crash. */
    public OrderResponseWithInfo toResponseWithInfo(Order order) {
        Checkout checkout = order.getCheckout();
        String refundStatus = checkout == null ? null : paymentGateway.refundStatus(checkout.getUid()).orElse(null);

        CustomUser chef = order.getChef();
        String chefName = "";
        if (chef != null) {
            // PORT-NOTE: getattr(chef, 'full_name', ...) always misses -> username. See class javadoc.
            chefName = chef.getUsername();
        }

        Optional<ChefProfile> profile = chef == null ? Optional.empty() : chefProfileRepository.findByUser(chef);
        if (profile.isEmpty()) {
            // PORT-NOTE: Django's else branch leaves chef_lat/chef_lng unbound ->
            // UnboundLocalError -> 500. Preserved; see class javadoc.
            throw new IllegalStateException(
                    "local variable 'chef_lat' referenced before assignment (chef has no ChefProfile)");
        }
        ChefProfile p = profile.get();
        String chefAddress = p.getKitchenAddress();
        Double chefLat = p.getKitchenLatitude();
        Double chefLng = p.getKitchenLongitude();
        if (chefAddress == null || chefAddress.isEmpty()) {
            List<String> components = new ArrayList<>();
            for (String c : new String[]{p.getKitchenStreet(), p.getKitchenWard(), p.getKitchenDistrict(), p.getKitchenCity()}) {
                if (c != null && !c.isEmpty()) {
                    components.add(c);
                }
            }
            if (!components.isEmpty()) {
                chefAddress = String.join(", ", components);
            }
        }
        if (chefAddress == null || chefAddress.isEmpty()) {
            chefAddress = "Chưa cập nhật địa chỉ bếp";
        }

        List<OrderItemResponse> items = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            Dish dish = item.getDish();
            items.add(new OrderItemResponse(
                    dish != null ? dish.getUid() : null,
                    item.getDishName(),
                    dish != null && dish.getOwner() != null ? fullName(dish.getOwner()) : null,
                    imageUrl(dish),
                    item.getQuantity(),
                    item.getPrice(),
                    item.subtotal()));
        }

        return new OrderResponseWithInfo(
                order.getUid(),
                checkout != null ? checkout.getFullName() : null,
                checkout != null ? checkout.getPhoneNumber() : null,
                checkout != null ? checkout.getDeliveryDate() : null,
                checkout != null ? checkout.getDeliveryTime() : null,
                checkout != null && checkout.getDeliveryAddress() != null ? checkout.getDeliveryAddress().fullAddress() : null,
                order.getDeliveryLatitude(),
                order.getDeliveryLongitude(),
                order.getDeliveryType().name(),
                chefName,
                chefAddress,
                chefLat,
                chefLng,
                checkout != null ? checkout.getPaymentMethod() : null,
                nz(order.getSubTotal()),
                nz(order.getTaxAndFees()),
                nz(order.getDeliveryFee()),
                nz(order.getPlatformSubtotalDiscount()),
                nz(order.getPlatformShippingDiscount()),
                nz(order.getShopDiscount()),
                nz(order.getTotalDiscount()),
                nz(order.getTotalPrice()),
                items,
                order.getStatus(),
                refundStatus,
                voucherCodes(order));
    }
}
