package com.amomeal.marketplace.cart.web;

import com.amomeal.marketplace.cart.dto.CartAddRequest;
import com.amomeal.marketplace.cart.dto.CartRemoveRequest;
import com.amomeal.marketplace.cart.dto.CartResponse;
import com.amomeal.marketplace.cart.dto.CartSetQuantityRequest;
import com.amomeal.marketplace.cart.service.CartService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Mirrors ../../backend/cart/api.py::CartController. Django's controller is
 * declared with {@code auth=AuthBear()} and no per-endpoint
 * {@code @require_group}/{@code @require_permission} decorator anywhere —
 * every route here is simply "any authenticated user" (CUSTOMER, CHEF or
 * ADMIN alike), matching Spring Security's default {@code anyRequest().authenticated()}
 * in {@code SecurityConfig} with no additional {@code @PreAuthorize}. Not
 * role-gated by design: this is "my cart", scoped by ownership
 * (the authenticated principal), not by role — confirmed by reading
 * {@code api.py} rather than assumed.
 *
 * <p>Paths/methods ported 1:1 from {@code api.py}: base prefix
 * {@code "carts"} under the shared {@code /api} root, so
 * {@code GET /api/carts/}, {@code GET /api/carts/count},
 * {@code POST /api/carts/add}, {@code PUT /api/carts/cart-items/{uid}/toggle},
 * {@code PUT /api/carts/items/{dish_uid}}, {@code DELETE /api/carts/items/{dish_uid}}.
 */
@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /** Django: {@code GET /api/carts/}. */
    @GetMapping({"", "/"})
    public CartResponse getCart(@AuthenticationPrincipal CustomUser user) {
        return cartService.getCartByUser(user);
    }

    /** Django: {@code GET /api/carts/count}. */
    @GetMapping("/count")
    public int getCartItemCount(@AuthenticationPrincipal CustomUser user) {
        return cartService.getCartItemCount(user);
    }

    /** Django: {@code POST /api/carts/add}. */
    @PostMapping("/add")
    public CartResponse addItem(@AuthenticationPrincipal CustomUser user, @Valid @RequestBody CartAddRequest payload) {
        return cartService.addItem(user, payload);
    }

    /**
     * Django: {@code PUT /api/carts/cart-items/{cart_item_uid}/toggle}. Post-port
     * fix: 403 when the item isn't in the caller's own cart (Django had no check).
     */
    @PutMapping("/cart-items/{cartItemUid}/toggle")
    public CartResponse toggleSelect(@AuthenticationPrincipal CustomUser user, @PathVariable UUID cartItemUid) {
        return cartService.toggleSelect(user, cartItemUid);
    }

    /** Django: {@code PUT /api/carts/items/{dish_uid}}. */
    @PutMapping("/items/{dishUid}")
    public CartResponse setQuantity(@AuthenticationPrincipal CustomUser user,
                                     @PathVariable UUID dishUid,
                                     @Valid @RequestBody CartSetQuantityRequest payload) {
        return cartService.setQuantity(user, dishUid, payload.deliveryDate(), payload.targetQuantity());
    }

    /** Django: {@code DELETE /api/carts/items/{dish_uid}}. */
    @DeleteMapping("/items/{dishUid}")
    public CartResponse removeItem(@AuthenticationPrincipal CustomUser user,
                                    @PathVariable UUID dishUid,
                                    @Valid @RequestBody CartRemoveRequest payload) {
        return cartService.removeItem(user, dishUid, payload.deliveryDate());
    }
}
