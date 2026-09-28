package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.order.dto.OrderFilterRequest;
import com.amomeal.marketplace.order.dto.OrderResponseWithInfo;
import com.amomeal.marketplace.order.service.OrderService;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/api.py::ChefOrderController ({@code prefix
 * "chef/orders"}, {@code auth=AuthBear()}, no group gate). Every action first
 * resolves the order (404 ORDER_NOT_FOUND) and then requires
 * {@code order.chef == request.user} — else {@code HttpError(403, "You don't have
 * permission to <verb> this order")} -&gt; 403 HTTP_ERROR. Strict: an ADMIN who is
 * not the order's chef is refused too, exactly as in Django.
 */
@RestController
@RequestMapping("/api/chef/orders")
@RequiredArgsConstructor
public class ChefOrderController {

    private final OrderService orderService;

    /** Django: {@code GET /api/chef/orders/} — {@code get_my_orders(user=chef)}, role-scoped. */
    @GetMapping({"", "/"})
    public List<OrderResponseWithInfo> getAllOrdersOfChef(@AuthenticationPrincipal CustomUser user,
                                                          @RequestParam(required = false) String search,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(name = "order_by", required = false) String orderBy,
                                                          @RequestParam(name = "sort_type", required = false) String sortType) {
        return orderService.getAllOrdersOfChef(user, new OrderFilterRequest(search, status, orderBy, sortType));
    }

    /** Django: {@code POST /api/chef/orders/{uid}/confirm}. */
    @PostMapping("/{uid}/confirm")
    public OrderResponseWithInfo confirmOrder(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        orderService.assertChefOwns(uid, user, "You don't have permission to confirm this order");
        return orderService.chefConfirmOrder(uid);
    }

    /** Django: {@code POST /api/chef/orders/{uid}/reject?reason=...}. */
    @PostMapping("/{uid}/reject")
    public OrderResponseWithInfo rejectOrder(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                             @RequestParam(required = false) String reason) {
        orderService.assertChefOwns(uid, user, "You don't have permission to reject this order");
        return orderService.cancelOrder(uid, reason != null ? reason : "Chef rejected order", "chef");
    }

    /** Django: {@code POST /api/chef/orders/{uid}/start-processing} (CONFIRMED_SHOP -&gt; PROCESSING). */
    @PostMapping("/{uid}/start-processing")
    public OrderResponseWithInfo startProcessing(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        orderService.assertChefOwns(uid, user, "You don't have permission to process this order");
        return orderService.startProcessing(uid);
    }

    /** Django: {@code POST /api/chef/orders/{uid}/start-delivery} (PROCESSING -&gt; DELIVERING). */
    @PostMapping("/{uid}/start-delivery")
    public OrderResponseWithInfo startDelivery(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        orderService.assertChefOwns(uid, user, "You don't have permission to deliver this order");
        return orderService.startDelivery(uid);
    }

    /** Django: {@code POST /api/chef/orders/{uid}/complete} (DELIVERING -&gt; COMPLETED + settlement). */
    @PostMapping("/{uid}/complete")
    public OrderResponseWithInfo completeOrder(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        orderService.assertChefOwns(uid, user, "You don't have permission to complete this order");
        return orderService.completeOrderWithRelease(uid);
    }
}
