package com.amomeal.marketplace.recommendation.support;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.dish.repository.DishIngredientRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared full-stack base (real Postgres + pgvector via Testcontainers, fake Gemini). All
 * recommendation integration tests extend it so they share ONE cached Spring context.
 * The external meal-parser AI service points at an unreachable port (fails fast → heuristic).
 */
@Import({TestcontainersConfiguration.class, RecommendationTestConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.gemini.api-key=test-gemini-key",
        "app.ai-model.base-url=http://127.0.0.1:1",
        "app.ai-model.timeout-seconds=1"
})
public abstract class RecommendationIntegrationTestBase {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected CustomUserRepository userRepository;
    @Autowired protected DishRepository dishRepository;
    @Autowired protected DishIngredientRepository dishIngredientRepository;
    @Autowired protected IngredientRepository ingredientRepository;
    @Autowired protected CheckoutRepository checkoutRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected OrderItemRepository orderItemRepository;
    @Autowired protected ChefProfileRepository chefProfileRepository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected FakeGeminiClient gemini;

    protected record Account(String token, CustomUser user) {
        public String bearer() {
            return "Bearer " + token;
        }

        public long id() {
            return user.getId();
        }
    }

    protected Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0901234567"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CustomUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
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

    protected Dish dish(Account chef, String name, double avgRating) {
        return dishRepository.save(Dish.builder().name(name).category(DishCategory.FOOD)
                .price(new BigDecimal("50000")).owner(chef.user()).avgRating(avgRating).build());
    }

    protected Ingredient ingredient(String name) {
        return ingredientRepository.save(Ingredient.builder().name(name + " " + UUID.randomUUID())
                .category(IngredientCategory.VEGETABLE).build());
    }

    protected DishIngredient line(Dish dish, Ingredient ingredient, double weight, double protein, double lipid,
                                  double carb, double natri, double fiber, double confidence) {
        return dishIngredientRepository.save(DishIngredient.builder().dish(dish).ingredient(ingredient)
                .customName(ingredient == null ? "custom" : null)
                .weight(weight).protein(protein).lipid(lipid).carbohydrate(carb).natri(natri).fiber(fiber)
                .confidence(confidence).build());
    }

    protected Order order(Account customer, Dish dish, int quantity, OrderStatus status, LocalTime deliveryTime) {
        Checkout checkout = checkoutRepository.save(Checkout.builder().owner(customer.user())
                .fullName(customer.user().getUsername()).phoneNumber("0900000000")
                .deliveryDate(LocalDate.now()).deliveryTime(deliveryTime).paymentMethod(PaymentMethod.COD).build());
        Order order = orderRepository.save(Order.builder().checkout(checkout).owner(customer.user())
                .chef(dish.getOwner()).status(status).build());
        orderItemRepository.save(OrderItem.builder().order(order).dish(dish).dishName(dish.getName())
                .quantity(quantity).price(dish.getPrice()).build());
        return order;
    }

    protected String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
