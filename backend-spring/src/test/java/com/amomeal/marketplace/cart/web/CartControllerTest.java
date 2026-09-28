package com.amomeal.marketplace.cart.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the cart module through the full stack (real
 * filter chain, real Postgres via the shared {@link TestcontainersConfiguration}),
 * mirroring ../../backend/cart/api.py. Modeled on
 * {@code menu.web.MenuControllerTest}'s pattern: register through the real
 * auth endpoint, then grant the account its Django role via the repository,
 * and build fixture dishes/availabilities through the real
 * {@code dish}-module HTTP endpoints (not injected directly), matching how
 * every prior full-stack test in this project sets up its cross-module data.
 *
 * <p>Cart's endpoints are NOT role-gated in Django (no
 * {@code @require_group}/{@code @require_permission} anywhere in
 * {@code cart/api.py}, just {@code auth=AuthBear()}) — every route only
 * requires <em>some</em> authenticated principal, scoped to that principal's
 * own cart by ownership, not by role. {@link #anyAuthenticatedRole_canUseTheirOwnCart}
 * proves this explicitly (a CHEF-role account uses its own cart same as a
 * CUSTOMER), rather than assuming the "my cart" framing implies a CUSTOMER-only
 * gate the way the task brief warned it might.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CartControllerTest {

    @Autowired
    private com.amomeal.marketplace.dish.repository.DishRepository dishRepository;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;

    private record Account(String token, Long userId) {
    }

    private Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000006"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        customUserRepository.save(user);
        return new Account(extractField(json, "access_token"), user.getId());
    }

    private UUID completedAttachment() {
        Attachment attachment = Attachment.builder()
                .type(AttachmentType.DISH)
                .originalName("mon.jpg")
                .hashedName("mon-" + UUID.randomUUID() + ".jpg")
                .size(1024)
                .contentType("image/jpeg")
                .bucket("amomeal-test-bucket")
                .directory("dish")
                .publicUrl("https://cdn.example.test/dish/mon.jpg")
                .isCompleted(true)
                .build();
        return attachmentRepository.save(attachment).getUid();
    }

    private String createDish(String chefToken, String name, int price) throws Exception {
        String body = """
                {"name":"%s","category":"FOOD","price":%d,"attachment_uid":"%s"}
                """.formatted(name, price, completedAttachment());
        String json = mockMvc.perform(post("/api/dishes/")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "uid");
    }

    private void createAvailability(String chefToken, String dishUid, LocalDate date, int quantity) throws Exception {
        mockMvc.perform(post("/api/dishes/" + dishUid + "/availabilities")
                        .header("Authorization", "Bearer " + chefToken)
                        .contentType("application/json")
                        .content("""
                                {"available_date":"%s","available_quantity":%d}
                                """.formatted(date, quantity)))
                .andExpect(status().isOk());
    }

    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("field " + field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    /**
     * Same as {@link #extractField} but finds the LAST occurrence — needed when the
     * cart already contains an earlier item grouped before the one just added
     * (date groups are ordered by delivery_date, so a later date's item's "uid" key
     * appears later in the response than an earlier date's).
     */
    private static String extractLastField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.lastIndexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("field " + field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    // ===================================================================
    // get cart / count
    // ===================================================================

    @Test
    void getCart_emptyCart_returnsEmptyStructure() throws Exception {
        Account customer = register("cart-get-empty", UserRole.CUSTOMER);

        mockMvc.perform(get("/api/carts/").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.total_amount").value(0.0))
                .andExpect(jsonPath("$.message_code").value("SUCCESS"));
    }

    @Test
    void getCart_unauthorized_isRejected() throws Exception {
        mockMvc.perform(get("/api/carts/"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCartItemCount_empty_isZero() throws Exception {
        Account customer = register("cart-count-empty", UserRole.CUSTOMER);
        mockMvc.perform(get("/api/carts/count").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    void anyAuthenticatedRole_canUseTheirOwnCart() throws Exception {
        // Not role-gated in Django (auth=AuthBear() only, no @require_group) --
        // a CHEF account's own cart works exactly like a CUSTOMER's.
        Account chef = register("cart-anyrole-chef", UserRole.CHEF);
        mockMvc.perform(get("/api/carts/").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk());

        Account admin = register("cart-anyrole-admin", UserRole.ADMIN);
        mockMvc.perform(get("/api/carts/").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());
    }

    // ===================================================================
    // add item
    // ===================================================================

    @Test
    void addItem_success_returnsCartWithItem() throws Exception {
        Account chef = register("cart-add-chef", UserRole.CHEF);
        Account customer = register("cart-add-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Bún Bò Huế " + System.nanoTime(), 45000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 10);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":2}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].delivery_date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].dish_uid").value(dishUid))
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].is_selected").value(false))
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].subtotal").value(90000.0));

        mockMvc.perform(get("/api/carts/count").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));
    }

    @Test
    void addItem_sameDishTwice_accumulatesQuantity() throws Exception {
        Account chef = register("cart-add-twice-chef", UserRole.CHEF);
        Account customer = register("cart-add-twice-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Gỏi Cuốn " + System.nanoTime(), 30000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 10);

        for (int qty : new int[]{2, 3}) {
            mockMvc.perform(post("/api/carts/add")
                            .header("Authorization", "Bearer " + customer.token())
                            .contentType("application/json")
                            .content("""
                                    {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":%d}
                                    """.formatted(dishUid, tomorrow, qty)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/carts/").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].quantity").value(5));
    }

    @Test
    void addItem_cappedAtAvailability() throws Exception {
        Account chef = register("cart-add-cap-chef", UserRole.CHEF);
        Account customer = register("cart-add-cap-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Bánh Xèo " + System.nanoTime(), 60000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 10);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":15}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].quantity").value(10))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("Chỉ còn 10")));
    }

    @Test
    void addItem_zeroQuantityToAdd_addsOneAnyway() throws Exception {
        Account chef = register("cart-add-zero-chef", UserRole.CHEF);
        Account customer = register("cart-add-zero-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Chả Giò " + System.nanoTime(), 20000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 10);

        // PORT-NOTE: Django's `quantity_to_add or 1` treats a literal 0 as falsy -> 1.
        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":0}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].quantity").value(1));
    }

    @Test
    void addItem_pastDeliveryDate_isRejected() throws Exception {
        Account chef = register("cart-add-past-chef", UserRole.CHEF);
        Account customer = register("cart-add-past-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Cơm Tấm " + System.nanoTime(), 35000);
        LocalDate yesterday = LocalDate.now().minusDays(1);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dishUid, yesterday)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("INVALID_DELIVERY_DATE"));
    }

    @Test
    void addItem_dishNotAvailableOnDate_isServerError() throws Exception {
        Account chef = register("cart-add-noavail-chef", UserRole.CHEF);
        Account customer = register("cart-add-noavail-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Bún Chả " + System.nanoTime(), 40000);
        LocalDate farFuture = LocalDate.now().plusDays(60);

        // Django: bare `Exception(...)`, uncaught -> 500 CONTACT_ADMIN_FOR_SUPPORT.
        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dishUid, farFuture)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void addItem_nonexistentDish_isServerError() throws Exception {
        Account customer = register("cart-add-ghostdish-cust", UserRole.CUSTOMER);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // PORT-NOTE: preserves Django's uncaught AttributeError-on-None-dish crash.
        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(UUID.randomUUID(), tomorrow)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void addItem_unauthorized_isRejected() throws Exception {
        mockMvc.perform(post("/api/carts/add")
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(UUID.randomUUID(), LocalDate.now().plusDays(1))))
                .andExpect(status().isUnauthorized());
    }

    // ===================================================================
    // toggle select
    // ===================================================================

    @Test
    void toggleSelect_toTrueThenBackToFalse() throws Exception {
        Account chef = register("cart-toggle-chef", UserRole.CHEF);
        Account customer = register("cart-toggle-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Phở Gà " + System.nanoTime(), 50000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 5);

        String addJson = mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String itemUid = extractField(addJson, "uid");

        mockMvc.perform(put("/api/carts/cart-items/" + itemUid + "/toggle")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].is_selected").value(true))
                .andExpect(jsonPath("$.data.total_amount").value(50000.0));

        mockMvc.perform(put("/api/carts/cart-items/" + itemUid + "/toggle")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].is_selected").value(false))
                .andExpect(jsonPath("$.data.total_amount").value(0.0));
    }

    @Test
    void toggleSelect_crossDeliveryDate_isServerError() throws Exception {
        Account chef = register("cart-toggle-crossdate-chef", UserRole.CHEF);
        Account customer = register("cart-toggle-crossdate-cust", UserRole.CUSTOMER);
        String dish1 = createDish(chef.token(), "Bánh Mì " + System.nanoTime(), 25000);
        String dish2 = createDish(chef.token(), "Xôi Gà " + System.nanoTime(), 28000);
        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = LocalDate.now().plusDays(2);
        createAvailability(chef.token(), dish1, day1, 5);
        createAvailability(chef.token(), dish2, day2, 5);

        String json1 = mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dish1, day1)))
                .andReturn().getResponse().getContentAsString();
        String item1Uid = extractField(json1, "uid");

        String json2 = mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dish2, day2)))
                .andReturn().getResponse().getContentAsString();
        // day2 > day1, so its date group (and this item's "uid") comes LAST in the
        // response now that the cart holds both items.
        String item2Uid = extractLastField(json2, "uid");

        mockMvc.perform(put("/api/carts/cart-items/" + item1Uid + "/toggle")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/carts/cart-items/" + item2Uid + "/toggle")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void toggleSelect_nonexistentUid_isServerError() throws Exception {
        Account customer = register("cart-toggle-ghost-cust", UserRole.CUSTOMER);

        mockMvc.perform(put("/api/carts/cart-items/" + UUID.randomUUID() + "/toggle")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void toggleSelect_anotherUsersCartItem_is403_andItemUnchanged_ownerStillCan_anonymous401() throws Exception {
        // Post-port authorization fix (2026-09-25): Django's toggle_select never checked ownership.
        Account chef = register("cart-toggle-idor-chef", UserRole.CHEF);
        Account owner = register("cart-toggle-idor-owner", UserRole.CUSTOMER);
        Account stranger = register("cart-toggle-idor-stranger", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Hủ Tiếu " + System.nanoTime(), 40000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 5);

        String addJson = mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dishUid, tomorrow)))
                .andReturn().getResponse().getContentAsString();
        String itemUid = extractField(addJson, "uid");

        mockMvc.perform(put("/api/carts/cart-items/" + itemUid + "/toggle")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("PERMISSION_DENIED"));
        mockMvc.perform(put("/api/carts/cart-items/" + itemUid + "/toggle"))
                .andExpect(status().isUnauthorized());
        // untouched by the stranger: the owner's first toggle selects it
        mockMvc.perform(put("/api/carts/cart-items/" + itemUid + "/toggle")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].is_selected").value(true));
    }

    @Test
    void addToCart_suspendedDish_is400_DISH_SUSPENDED() throws Exception {
        Account chef = register("cart-susp-chef", UserRole.CHEF);
        Account customer = register("cart-susp-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Bún Bò " + System.nanoTime(), 40000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 5);
        dishRepository.findByUid(UUID.fromString(dishUid)).ifPresent(d -> {
            d.setSuspended(true);
            dishRepository.save(d);
        });

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":1}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DISH_SUSPENDED"));
    }

    // ===================================================================
    // set quantity / remove
    // ===================================================================

    @Test
    void setQuantity_updatesCartItem() throws Exception {
        Account chef = register("cart-setqty-chef", UserRole.CHEF);
        Account customer = register("cart-setqty-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Phở Gà " + System.nanoTime(), 50000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 8);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":3}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/carts/items/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"delivery_date":"%s","target_quantity":5}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].quantity").value(5));
    }

    @Test
    void setQuantity_toZero_removesItem() throws Exception {
        Account chef = register("cart-setqty0-chef", UserRole.CHEF);
        Account customer = register("cart-setqty0-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Phở Gà " + System.nanoTime(), 50000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 8);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":3}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/carts/items/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"delivery_date":"%s","target_quantity":0}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(get("/api/carts/count").header("Authorization", "Bearer " + customer.token()))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    void setQuantity_cappedAtAvailability() throws Exception {
        Account chef = register("cart-setqtycap-chef", UserRole.CHEF);
        Account customer = register("cart-setqtycap-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Phở Gà " + System.nanoTime(), 50000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 8);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":3}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/carts/items/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"delivery_date":"%s","target_quantity":100}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chefs[0].items[0].quantity").value(8));
    }

    @Test
    void removeItem_success_andItemCountReflectsRemoval() throws Exception {
        Account chef = register("cart-remove-chef", UserRole.CHEF);
        Account customer = register("cart-remove-cust", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Phở Gà " + System.nanoTime(), 50000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 8);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":3}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/carts/items/" + dishUid)
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"delivery_date":"%s"}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(get("/api/carts/count").header("Authorization", "Bearer " + customer.token()))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    void removeItem_nonexistentItem_isSilentNoOp() throws Exception {
        Account customer = register("cart-remove-ghost-cust", UserRole.CUSTOMER);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        mockMvc.perform(delete("/api/carts/items/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json")
                        .content("""
                                {"delivery_date":"%s"}
                                """.formatted(tomorrow)))
                .andExpect(status().isOk());
    }

    @Test
    void cartsAreIsolatedPerUser() throws Exception {
        Account chef = register("cart-isolated-chef", UserRole.CHEF);
        Account customerA = register("cart-isolated-a", UserRole.CUSTOMER);
        Account customerB = register("cart-isolated-b", UserRole.CUSTOMER);
        String dishUid = createDish(chef.token(), "Bún Riêu " + System.nanoTime(), 40000);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        createAvailability(chef.token(), dishUid, tomorrow, 8);

        mockMvc.perform(post("/api/carts/add")
                        .header("Authorization", "Bearer " + customerA.token())
                        .contentType("application/json")
                        .content("""
                                {"dish_uid":"%s","delivery_date":"%s","quantity_to_add":2}
                                """.formatted(dishUid, tomorrow)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/carts/count").header("Authorization", "Bearer " + customerB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }
}
