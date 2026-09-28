package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.order.dto.ApplyVoucherRequest;
import com.amomeal.marketplace.order.dto.OrderFilterRequest;
import com.amomeal.marketplace.order.dto.OrderListResponse;
import com.amomeal.marketplace.order.dto.OrderResponseWithInfo;
import com.amomeal.marketplace.order.service.OrderService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/api.py::OrderController ({@code prefix "orders"},
 * {@code auth=AuthBear()}, no {@code @require_group}/{@code @require_permission}
 * on any route — confirmed line by line). Authorization in Django's order app is
 * therefore <b>data scoping by role</b> ({@code get_my_orders}: CUSTOMER sees
 * orders they own, CHEF orders they cook, ADMIN everything) plus the chef
 * ownership 403s in {@link ChefOrderController} — not role gates; ported as such.
 *
 * <p>FE-admin never calls these (its order screens use {@code /api/admin/orders},
 * owned by the not-yet-ported {@code admin} module) — this is the customer/chef
 * app surface; shapes were cross-checked against Django's schemas.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** Django: {@code GET /api/orders/} — role-scoped (see class javadoc). */
    @GetMapping({"", "/"})
    public List<OrderListResponse> getMyOrders(@AuthenticationPrincipal CustomUser user,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(name = "order_by", required = false) String orderBy,
                                               @RequestParam(name = "sort_type", required = false) String sortType) {
        return orderService.getMyOrders(user, new OrderFilterRequest(search, status, orderBy, sortType));
    }

    /** Django: {@code GET /api/orders/customer} — always the caller's own orders as customer. */
    @GetMapping("/customer")
    public List<OrderListResponse> getCustomerOrders(@AuthenticationPrincipal CustomUser user,
                                                     @RequestParam(required = false) String search,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(name = "order_by", required = false) String orderBy,
                                                     @RequestParam(name = "sort_type", required = false) String sortType) {
        return orderService.getCustomerOrders(user, new OrderFilterRequest(search, status, orderBy, sortType));
    }

    /** Django: {@code GET /api/orders/{uid}}. Only the order's customer, its chef or ADMIN (guard added post-port). */
    @GetMapping("/{uid}")
    public OrderResponseWithInfo getOrderByUid(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        orderService.assertCanReadOrder(uid, user);
        return orderService.getOrderByUid(uid);
    }

    /**
     * Django: {@code POST /api/orders/{uid}/cancel?reason=...} (customer cancel).
     * Post-port fix: only the owning customer may cancel (Django had no check). The Firebase push to
     * the chef that follows in Django is dropped with mongo_chat.
     */
    @PostMapping("/{uid}/cancel")
    public OrderResponseWithInfo cancelOrderByCustomer(@AuthenticationPrincipal CustomUser user,
                                                       @PathVariable UUID uid,
                                                       @RequestParam(required = false) String reason) {
        orderService.assertOwnsOrder(uid, user, "You don't have permission to cancel this order");
        return orderService.cancelOrder(uid, reason, "customer");
    }

    /** Django: {@code POST /api/orders/{uid}/apply-voucher} (SHOP_VOUCHER only). */
    @PostMapping("/{uid}/apply-voucher")
    public OrderResponseWithInfo applyVoucherToOrder(@AuthenticationPrincipal CustomUser user,
                                                     @PathVariable UUID uid,
                                                     @Valid @RequestBody ApplyVoucherRequest payload) {
        orderService.assertOwnsOrder(uid, user, "You don't have permission to modify this order");
        return orderService.applyShopVoucherToOrder(user, uid, payload.voucherCode());
    }
}
