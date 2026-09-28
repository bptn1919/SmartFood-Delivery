package com.amomeal.marketplace.review.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack tests of the `review` module over real HTTP + real Postgres
 * (Testcontainers). Users come from the real {@code /api/auth/register}
 * endpoint with roles assigned via the repository (same convention as every
 * other module's controller test); a COMPLETED order is built directly
 * through {@code order}'s repositories (permitted by the task — "build a
 * completed order through real order endpoints or repositories").
 *
 * <p>{@code app.ai-model.base-url} points at an unreachable address on
 * purpose (same trick as {@code order}'s Ahamove tests) — every review write
 * in this class exercises the REAL fallback path in
 * {@link com.amomeal.marketplace.review.service.RestClientAiModelClient},
 * proving end-to-end that an unavailable AI model never blocks a review
 * (weight stays 0.0, issue stays null, HTTP 200).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ai-model.base-url=http://127.0.0.1:1",
        "app.ai-model.timeout-seconds=2"
})
class ReviewControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomUserRepository userRepository;
    @Autowired DishRepository dishRepository;
    @Autowired CheckoutRepository checkoutRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired ChefProfileRepository chefProfileRepository;

    private record Account(String token, CustomUser user) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    private Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0901234567"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CustomUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        user.setFirstName(prefix);
        user.setLastName("Nguyen");
        user = userRepository.save(user);
        return new Account(objectMapper.readTree(json).get("data").get("access_token").asString(), user);
    }

    private Account chef(String prefix) throws Exception {
        Account chef = register(prefix, UserRole.CHEF);
        chefProfileRepository.save(ChefProfile.builder().user(chef.user())
                .kitchenStreet("1 Bếp").kitchenWard("W1").kitchenDistrict("D1").kitchenCity("HCM")
                .kitchenLatitude(10.77).kitchenLongitude(106.70).build());
        return chef;
    }

    private Dish dish(Account chef, String name, String price) {
        return dishRepository.save(Dish.builder().name(name + " " + UUID.randomUUID())
                .category(DishCategory.FOOD).price(new BigDecimal(price)).owner(chef.user()).build());
    }

    /** Builds a COMPLETED order (with one item for {@code dish}) directly via repositories. */
    private Order completedOrder(Account customer, Dish dish, int quantity) {
        Checkout checkout = checkoutRepository.save(Checkout.builder()
                .owner(customer.user())
                .fullName(customer.user().getUsername())
                .phoneNumber("0900000000")
                .deliveryDate(LocalDate.now())
                .deliveryTime(LocalTime.NOON)
                .paymentMethod(PaymentMethod.COD)
                .build());
        Order order = orderRepository.save(Order.builder()
                .checkout(checkout)
                .owner(customer.user())
                .chef(dish.getOwner())
                .status(OrderStatus.COMPLETED)
                .build());
        orderItemRepository.save(OrderItem.builder()
                .order(order)
                .dish(dish)
                .dishName(dish.getName())
                .quantity(quantity)
                .price(dish.getPrice())
                .build());
        return order;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Account who) throws Exception {
        return mockMvc.perform(request.header("Authorization", who.bearer()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    // =====================================================================
    // create_review — happy path + business rules
    // =====================================================================

    @Test
    void createReview_fullFlow_updatesDishAvgRatingAndChefRating_aiUnavailableStillSaves() throws Exception {
        Account chef = chef("revchef");
        Account customer = register("revcust", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Pho", "60000");
        Order order = completedOrder(customer, dish, 2);

        String body = """
                {"dish_uid":"%s","order_uid":"%s","rating":5,"comment":"delicious!"}
                """.formatted(dish.getUid(), order.getUid());

        JsonNode created = data(call(post("/api/reviews/").contentType("application/json").content(body), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error_code").value(0))
                .andExpect(jsonPath("$.data.rating").value(5)));

        // AI model unreachable -> fallback (weight 0.0, issue null) -- the review still saved.
        assertThat(created.get("issue").isNull()).isTrue();

        Dish reloaded = dishRepository.findByUid(dish.getUid()).orElseThrow();
        assertThat(reloaded.getAvgRating()).isEqualTo(5.0);
        ChefProfile chefProfile = chefProfileRepository.findByUserId(chef.user().getId()).orElseThrow();
        assertThat(chefProfile.getRating()).isEqualTo(5.0);
    }

    @Test
    void createReview_ratingOutOfRange_isInvalidRating_withFileWideStatusCodeQuirk() throws Exception {
        Account chef = chef("revchef2");
        Account customer = register("revcust2", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Bun Bo", "55000");
        Order order = completedOrder(customer, dish, 1);

        String body = """
                {"dish_uid":"%s","order_uid":"%s","rating":6}
                """.formatted(dish.getUid(), order.getUid());

        // PORT-NOTE: every exceptions/reviews.py class sets `status_code`, not the attribute
        // Django's handler actually reads (`error_code`) -- so this is really a 500, not 400.
        // See InvalidRatingException's javadoc.
        call(post("/api/reviews/").contentType("application/json").content(body), customer)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("INVALID_RATING"))
                .andExpect(jsonPath("$.error_code").value(500));
    }

    @Test
    void createReview_dishNotInOrder_is404() throws Exception {
        Account chef = chef("revchef3");
        Account customer = register("revcust3", UserRole.CUSTOMER);
        Dish orderedDish = dish(chef, "Com Tam", "45000");
        Dish otherDish = dish(chef, "Banh Mi", "20000");
        Order order = completedOrder(customer, orderedDish, 1);

        String body = """
                {"dish_uid":"%s","order_uid":"%s","rating":4}
                """.formatted(otherDish.getUid(), order.getUid());

        call(post("/api/reviews/").contentType("application/json").content(body), customer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("DISH_NOT_FOUND_IN_ORDER"));
    }

    @Test
    void createReview_orderNotCompleted_isRejected() throws Exception {
        Account chef = chef("revchef4");
        Account customer = register("revcust4", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Goi Cuon", "30000");
        Order order = completedOrder(customer, dish, 1);
        order.setStatus(OrderStatus.PENDING);
        orderRepository.save(order);

        String body = """
                {"dish_uid":"%s","order_uid":"%s","rating":4}
                """.formatted(dish.getUid(), order.getUid());

        call(post("/api/reviews/").contentType("application/json").content(body), customer)
                .andExpect(jsonPath("$.message_code").value("ORDER_NOT_COMPLETED"));
    }

    @Test
    void createReview_duplicate_isRejectedOnSecondAttempt() throws Exception {
        Account chef = chef("revchef5");
        Account customer = register("revcust5", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Cha Gio", "25000");
        Order order = completedOrder(customer, dish, 1);
        String body = """
                {"dish_uid":"%s","order_uid":"%s","rating":4}
                """.formatted(dish.getUid(), order.getUid());

        call(post("/api/reviews/").contentType("application/json").content(body), customer).andExpect(status().isOk());
        call(post("/api/reviews/").contentType("application/json").content(body), customer)
                .andExpect(jsonPath("$.message_code").value("DUPLICATE_REVIEW"));
    }

    // =====================================================================
    // reply — owner-only rules
    // =====================================================================

    @Test
    void reviewReply_onlyDishOwnerChefCanReply_andOnlyOnce() throws Exception {
        Account chef = chef("revchef6");
        Account otherChef = chef("revotherchef6");
        Account customer = register("revcust6", UserRole.CUSTOMER);
        Dish dish = dish(chef, "Banh Xeo", "40000");
        Order order = completedOrder(customer, dish, 1);
        String createBody = """
                {"dish_uid":"%s","order_uid":"%s","rating":3}
                """.formatted(dish.getUid(), order.getUid());
        JsonNode review = data(call(post("/api/reviews/").contentType("application/json").content(createBody), customer)
                .andExpect(status().isOk()));
        UUID reviewUid = UUID.fromString(review.get("uid").asString());

        // Non-owner chef cannot reply.
        call(post("/api/reviews/" + reviewUid + "/reply").contentType("application/json")
                        .content("{\"content\":\"nope\"}"), otherChef)
                .andExpect(jsonPath("$.message_code").value("NOT_DISH_OWNER"));

        // Owning chef can reply.
        call(post("/api/reviews/" + reviewUid + "/reply").contentType("application/json")
                        .content("{\"content\":\"Thanks for the feedback!\"}"), chef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("Thanks for the feedback!"));

        // A second reply on the same review is rejected.
        call(post("/api/reviews/" + reviewUid + "/reply").contentType("application/json")
                        .content("{\"content\":\"again\"}"), chef)
                .andExpect(jsonPath("$.message_code").value("REVIEW_REPLY_ALREADY_EXISTS"));
    }

    // =====================================================================
    // Stats seam -> dish top-dish Bayesian ranking
    // =====================================================================

    @Test
    void topDishRanking_reflectsRealReviewRatings_notJustNameOrSoldCount() throws Exception {
        Account chef = chef("revchef7");
        Account customer = register("revcust7", UserRole.CUSTOMER);
        // Alphabetically "Apple..." sorts before "Zebra...", so a ranking driven only by the
        // name tie-break (or by equal sold_count) would put Apple first -- proving the ordering
        // below is genuinely rating-driven, not incidental.
        Dish zebraDish = dish(chef, "Zebra Special", "70000");
        Dish appleDish = dish(chef, "Apple Special", "70000");

        for (int i = 0; i < 5; i++) {
            Order order = completedOrder(customer, zebraDish, 1);
            String body = """
                    {"dish_uid":"%s","order_uid":"%s","rating":5}
                    """.formatted(zebraDish.getUid(), order.getUid());
            call(post("/api/reviews/").contentType("application/json").content(body), customer).andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            Order order = completedOrder(customer, appleDish, 1);
            String body = """
                    {"dish_uid":"%s","order_uid":"%s","rating":1}
                    """.formatted(appleDish.getUid(), order.getUid());
            call(post("/api/reviews/").contentType("application/json").content(body), customer).andExpect(status().isOk());
        }

        JsonNode top = data(mockMvc.perform(get("/api/dishes/top").param("limit", "50")
                        .header("Authorization", customer.bearer()))
                .andExpect(status().isOk()));

        JsonNode zebraRow = findByUid(top, zebraDish.getUid());
        JsonNode appleRow = findByUid(top, appleDish.getUid());

        assertThat(zebraRow.get("review_count").asLong()).isEqualTo(5);
        assertThat(appleRow.get("review_count").asLong()).isEqualTo(5);
        assertThat(zebraRow.get("avg_rating").asDouble()).isEqualTo(5.0);
        assertThat(appleRow.get("avg_rating").asDouble()).isEqualTo(1.0);
        // Bayesian score: zebra's own avg (5.0) is above the platform-wide average (3.0, over
        // all 10 reviews across both dishes), apple's is below it -- zebra must outrank apple.
        assertThat(zebraRow.get("score").asDouble()).isGreaterThan(appleRow.get("score").asDouble());
    }

    private static JsonNode findByUid(JsonNode array, UUID uid) {
        for (JsonNode node : array) {
            if (uid.toString().equals(node.get("uid").asString())) {
                return node;
            }
        }
        throw new AssertionError("dish " + uid + " not found in /api/dishes/top response: " + array);
    }
}
