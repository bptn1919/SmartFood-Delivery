package com.amomeal.marketplace.report.support;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
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
import com.amomeal.marketplace.recommendation.support.FakeGeminiClient;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Shared full-stack fixtures for the report module: real Postgres/Redis (Testcontainers), real /api/auth/register
 * with roles assigned via the repository, COMPLETED orders built through order's repositories, and a FAKE Gemini
 * (the real API is never called).
 */
@Import({TestcontainersConfiguration.class, ReportTestConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.gemini.api-key=test-key")
public abstract class ReportIntegrationTestBase {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected CustomUserRepository userRepository;
    @Autowired protected DishRepository dishRepository;
    @Autowired protected CheckoutRepository checkoutRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected OrderItemRepository orderItemRepository;
    @Autowired protected ChefProfileRepository chefProfileRepository;
    @Autowired protected AttachmentRepository attachmentRepository;
    @Autowired protected FakeGeminiClient gemini;

    protected record Account(String token, CustomUser user) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    @BeforeEach
    void resetGemini() {
        gemini.reset();
    }

    /** Gemini answers with the given severity JSON for every prompt. */
    protected void geminiSays(String severity, boolean risk) {
        gemini.respondWith(p -> "{\"severity\":\"" + severity + "\",\"food_safety_risk\":" + risk
                + ",\"keywords_detected\":[],\"confidence\":\"high\",\"reason\":\"test\"}");
    }

    protected Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0901234567"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/register").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        CustomUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        user.setFirstName(prefix);
        user.setLastName("Nguyen");
        user = userRepository.save(user);
        return new Account(objectMapper.readTree(json).get("data").get("access_token").asString(), user);
    }

    protected Account chef(String prefix) throws Exception {
        Account chef = register(prefix, UserRole.CHEF);
        chefProfileRepository.save(ChefProfile.builder().user(chef.user())
                .kitchenStreet("1 Bếp").kitchenWard("W1").kitchenDistrict("D1").kitchenCity("HCM")
                .kitchenLatitude(10.77).kitchenLongitude(106.70).build());
        return chef;
    }

    protected Dish dish(Account chef, String name) {
        return dishRepository.save(Dish.builder().name(name + " " + UUID.randomUUID())
                .category(DishCategory.FOOD).price(new BigDecimal("50000")).owner(chef.user()).build());
    }

    /** {@code count} orders in the given status for {@code customer}, each with one item of {@code dish}. */
    protected java.util.List<Order> orders(Account customer, Dish dish, int count, OrderStatus status) {
        Checkout checkout = checkoutRepository.save(Checkout.builder()
                .owner(customer.user())
                .fullName(customer.user().getUsername())
                .phoneNumber("0900000000")
                .deliveryDate(LocalDate.now())
                .deliveryTime(LocalTime.NOON)
                .paymentMethod(PaymentMethod.COD)
                .build());
        java.util.List<Order> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Order order = orderRepository.save(Order.builder()
                    .checkout(checkout).owner(customer.user()).chef(dish.getOwner()).status(status).build());
            orderItemRepository.save(OrderItem.builder().order(order).dish(dish).dishName(dish.getName())
                    .quantity(1).price(dish.getPrice()).build());
            out.add(order);
        }
        return out;
    }

    protected Order completedOrder(Account customer, Dish dish) {
        return orders(customer, dish, 1, OrderStatus.COMPLETED).get(0);
    }

    protected Attachment evidence() {
        return attachmentRepository.save(Attachment.builder()
                .type(AttachmentType.REPORT).originalName("proof.jpg").hashedName("proof-" + UUID.randomUUID() + ".jpg")
                .size(1024).contentType("image/jpeg").bucket("local").directory("report")
                .publicUrl("http://localhost:8000/media/report/proof.jpg").build());
    }

    protected ResultActions call(MockHttpServletRequestBuilder request, Account who) throws Exception {
        return mockMvc.perform(request.header("Authorization", who.bearer()));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    protected String json(String template, Object... args) {
        return template.formatted(args);
    }
}
