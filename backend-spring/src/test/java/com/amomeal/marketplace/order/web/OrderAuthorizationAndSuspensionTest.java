package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Post-port decisions (2026-09-25), replacing the old "any authenticated user can read/cancel any order"
 * regression: order/checkout ownership guards (owner ok / other user 403 / admin where applicable /
 * anonymous 401) and suspension enforcement (CHEF_SUSPENDED, DISH_SUSPENDED) on checkout + place-order.
 */
class OrderAuthorizationAndSuspensionTest extends AbstractOrderFullStackTest {

    private static final String JSON = "application/json";

    // ---------------------------------------------------------------- read

    @Test
    void readOrder_ownerChefAdminOk_strangerForbidden_anonymousUnauthorized() throws Exception {
        Account chef = chef("authchef");
        Account owner = customerWithAddress("authowner");
        Account stranger = customerWithAddress("authstranger");
        Account otherChef = chef("authotherchef");
        Account admin = register("authadmin", UserRole.ADMIN);
        selectInCart(owner, dish(chef, "Z", "10000", 5), 1);
        UUID co = checkout(owner);
        call(post("/api/checkouts/" + co + "/place-order"), owner).andExpect(status().isOk());
        UUID orderUid = onlyOrder(co).getUid();

        call(get("/api/orders/" + orderUid), owner).andExpect(status().isOk());
        call(get("/api/orders/" + orderUid), chef).andExpect(status().isOk());
        call(get("/api/orders/" + orderUid), admin).andExpect(status().isOk());
        call(get("/api/orders/" + orderUid), stranger).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));
        call(get("/api/orders/" + orderUid), otherChef).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/" + orderUid)).andExpect(status().isUnauthorized());
        call(get("/api/orders/" + UUID.randomUUID()), stranger).andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------- cancel

    @Test
    void customerCancel_onlyTheOwner() throws Exception {
        Account chef = chef("cancchef");
        Account owner = customerWithAddress("cancowner");
        Account stranger = customerWithAddress("cancstranger");
        Account admin = register("cancadmin", UserRole.ADMIN);
        Dish dish = dish(chef, "Z", "10000", 5);
        selectInCart(owner, dish, 1);
        UUID co = checkout(owner);
        call(post("/api/checkouts/" + co + "/place-order"), owner).andExpect(status().isOk());
        UUID orderUid = onlyOrder(co).getUid();

        call(post("/api/orders/" + orderUid + "/cancel"), stranger).andExpect(status().isForbidden());
        call(post("/api/orders/" + orderUid + "/cancel"), admin).andExpect(status().isForbidden());
        call(post("/api/orders/" + orderUid + "/cancel"), chef).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/" + orderUid + "/cancel")).andExpect(status().isUnauthorized());
        assertThat(reload(orderUid).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(committed(dish)).isEqualTo(4);

        call(post("/api/orders/" + orderUid + "/cancel"), owner).andExpect(status().isOk());
        assertThat(reload(orderUid).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(committed(dish)).isEqualTo(5);
    }

    // ------------------------------------------------------------ vouchers

    @Test
    void applyVouchers_onlyTheOwner() throws Exception {
        Account chef = chef("vchef");
        Account owner = customerWithAddress("vowner");
        Account stranger = customerWithAddress("vstranger");
        selectInCart(owner, dish(chef, "Z", "100000", 5), 1);
        UUID co = checkout(owner);
        UUID orderUid = onlyOrder(co).getUid();

        call(post("/api/orders/" + orderUid + "/apply-voucher").contentType(JSON)
                .content("{\"voucher_code\":\"NOPE\"}"), stranger).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));
        call(post("/api/checkouts/" + co + "/apply-platform-voucher").contentType(JSON)
                .content("{\"voucher_code\":\"NOPE\",\"voucher_type\":\"PLATFORM_SUBTOTAL\"}"), stranger)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));
        // the owner passes the guard (and then fails on the unknown code, not on authorization)
        call(post("/api/orders/" + orderUid + "/apply-voucher").contentType(JSON)
                .content("{\"voucher_code\":\"NOPE\"}"), owner)
                .andExpect(jsonPath("$.message_code").value(org.hamcrest.Matchers.not("HTTP_ERROR")));
    }

    // ------------------------------------------------------------ checkout

    @Test
    void checkoutEditsAndPlaceOrder_onlyTheOwner() throws Exception {
        Account chef = chef("coechef");
        Account owner = customerWithAddress("coeowner");
        Account stranger = customerWithAddress("coestranger");
        Account admin = register("coeadmin", UserRole.ADMIN);
        Dish dish = dish(chef, "Canh", "50000", 5);
        selectInCart(owner, dish, 1);
        UUID co = checkout(owner);
        String base = "/api/checkouts/" + co;
        String types = "{\"sub_orders\":[{\"chef_id\":%d,\"delivery_type\":\"SELF_PICKUP\"}]}".formatted(chef.user().getId());

        for (Account intruder : new Account[]{stranger, admin, chef}) {
            call(patch(base + "/profile").contentType(JSON)
                    .content("{\"full_name\":\"X\",\"phone_number\":\"0999\"}"), intruder).andExpect(status().isForbidden());
            call(patch(base + "/payment-method").contentType(JSON).content("\"COD\""), intruder).andExpect(status().isForbidden());
            call(patch(base + "/delivery-time").contentType(JSON).content("\"18:30:00\""), intruder).andExpect(status().isForbidden());
            call(patch(base + "/delivery-address/1"), intruder).andExpect(status().isForbidden());
            call(patch(base + "/delivery-types").contentType(JSON).content(types), intruder).andExpect(status().isForbidden());
            call(post(base + "/place-order"), intruder).andExpect(status().isForbidden());
        }
        mockMvc.perform(post(base + "/place-order")).andExpect(status().isUnauthorized());
        call(patch("/api/checkouts/" + UUID.randomUUID() + "/profile").contentType(JSON)
                .content("{\"full_name\":\"X\",\"phone_number\":\"0999\"}"), stranger).andExpect(status().isNotFound());

        // nothing was reserved or changed by the refused calls
        assertThat(committed(dish)).isEqualTo(5);
        assertThat(onlyOrder(co).getStatus()).isEqualTo(OrderStatus.DRAFT);
        call(patch(base + "/profile").contentType(JSON).content("{\"full_name\":\"Chu\",\"phone_number\":\"0999\"}"), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.full_name").value("Chu"));
        call(post(base + "/place-order"), owner).andExpect(status().isOk());
    }

    // ---------------------------------------------------------- suspension

    private void setChef(Account chef, boolean accepting, ChefSuspensionLevel level) {
        ChefProfile p = chefProfileRepository.findByUser(chef.user()).orElseThrow();
        p.setAcceptingOrders(accepting);
        p.setSuspensionLevel(level);
        chefProfileRepository.save(p);
    }

    private void setDishSuspended(Dish dish, boolean suspended) {
        Dish d = dishRepository.findByUid(dish.getUid()).orElseThrow();
        d.setSuspended(suspended);
        dishRepository.save(d);
    }

    @Test
    void checkout_chefNotAcceptingOrders_isBlocked_CHEF_SUSPENDED() throws Exception {
        Account chef = chef("suschef");
        Account customer = customerWithAddress("suscus");
        selectInCart(customer, dish(chef, "Z", "10000", 5), 1);
        setChef(chef, false, ChefSuspensionLevel.SUSPENDED);

        call(post("/api/checkouts/"), customer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("CHEF_SUSPENDED"));
    }

    @Test
    void checkout_chefFullLockedButAcceptingFlagTrue_isStillBlocked() throws Exception {
        Account chef = chef("suschef2");
        Account customer = customerWithAddress("suscus2");
        selectInCart(customer, dish(chef, "Z", "10000", 5), 1);
        setChef(chef, true, ChefSuspensionLevel.SUSPENDED);

        call(post("/api/checkouts/"), customer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("CHEF_SUSPENDED"));
    }

    @Test
    void checkout_suspendedDish_isBlocked_DISH_SUSPENDED() throws Exception {
        Account chef = chef("susdchef");
        Account customer = customerWithAddress("susdcus");
        Dish dish = dish(chef, "Z", "10000", 5);
        selectInCart(customer, dish, 1);
        setDishSuspended(dish, true);

        call(post("/api/checkouts/"), customer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DISH_SUSPENDED"));
    }

    @Test
    void placeOrder_chefSuspendedAfterCheckout_isBlocked_andNothingReserved() throws Exception {
        Account chef = chef("suspchef");
        Account customer = customerWithAddress("suspcus");
        Dish dish = dish(chef, "Z", "10000", 5);
        selectInCart(customer, dish, 1);
        UUID co = checkout(customer);
        setChef(chef, false, ChefSuspensionLevel.SUSPENDED);

        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("CHEF_SUSPENDED"));
        assertThat(onlyOrder(co).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(committed(dish)).isEqualTo(5);
        assertThat(redisCounter(dish)).isNull();

        // lifting the suspension restores the money path
        setChef(chef, true, ChefSuspensionLevel.NONE);
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
    }

    @Test
    void placeOrder_dishSuspendedAfterCheckout_isBlocked_andNothingReserved() throws Exception {
        Account chef = chef("suspdchef");
        Account customer = customerWithAddress("suspdcus");
        Dish dish = dish(chef, "Z", "10000", 5);
        selectInCart(customer, dish, 1);
        UUID co = checkout(customer);
        setDishSuspended(dish, true);

        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DISH_SUSPENDED"));
        assertThat(onlyOrder(co).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(committed(dish)).isEqualTo(5);
    }
}
