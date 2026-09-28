package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.cart.entity.CartItem;
import com.amomeal.marketplace.cart.repository.CartItemRepository;
import com.amomeal.marketplace.cart.service.CartService;
import com.amomeal.marketplace.dish.config.StockRedisClient;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.dish.repository.StockReservationRepository;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the order module's full-stack tests (real filter chain,
 * real Postgres AND Redis via {@link TestcontainersConfiguration}). Users come
 * from the real {@code /api/auth/register} endpoint with their Django group set
 * via the repository, like every other module's controller test; dish/cart/
 * profile rows the order flow merely reads are inserted through their
 * repositories.
 *
 * <p>Not a {@code *Test} class itself (abstract; Surefire skips it).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // the @Scheduled sweep must not race assertions; tests drive sweepOnce() explicitly
        "app.stock.sweep-enabled=false",
        // Ahamove: unreachable on purpose -> exercises the real 15000 fallback, no internet
        "app.shipping.ahamove.base-url=http://127.0.0.1:1",
        "app.order.notification.retry-delay-ms=50"
})
abstract class AbstractOrderFullStackTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected CustomUserRepository userRepository;
    @Autowired protected DishRepository dishRepository;
    @Autowired protected DishAvailabilityRepository availabilityRepository;
    @Autowired protected StockReservationRepository reservationRepository;
    @Autowired protected StockRedisClient redis;
    @Autowired protected ChefProfileRepository chefProfileRepository;
    @Autowired protected ChefPaymentInfoRepository chefPaymentInfoRepository;
    @Autowired protected CustomerAddressRepository addressRepository;
    @Autowired protected CartService cartService;
    @Autowired protected CartItemRepository cartItemRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected OrderItemRepository orderItemRepository;

    protected final LocalDate date = LocalDate.now().plusDays(1);

    protected record Account(String token, CustomUser user) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    protected Account register(String prefix, UserRole role) throws Exception {
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

    /** A CHEF as created by the real upgrade-to-chef flow would look: with a ChefProfile. */
    protected Account chef(String prefix) throws Exception {
        Account chef = register(prefix, UserRole.CHEF);
        chefProfileRepository.save(ChefProfile.builder().user(chef.user())
                .kitchenStreet("12 Bếp St").kitchenWard("W1").kitchenDistrict("D1").kitchenCity("HCM")
                .kitchenLatitude(10.77).kitchenLongitude(106.70).build());
        return chef;
    }

    protected void verifyChefBank(Account chef) {
        chefPaymentInfoRepository.save(ChefPaymentInfo.builder().user(chef.user()).bankName("VCB").bankCode("VCB")
                .bankAccountNumber("0123").bankAccountName("CHEF").isVerified(true).build());
    }

    protected Account customerWithAddress(String prefix) throws Exception {
        Account customer = register(prefix, UserRole.CUSTOMER);
        addressRepository.save(CustomerAddress.builder().user(customer.user()).address("7").street("Lê Lợi")
                .ward("Bến Nghé").district("Q1").city("HCM").latitude(10.776).longitude(106.701).build());
        return customer;
    }

    protected Dish dish(Account chef, String name, String price, int quantityOnDate) {
        Dish dish = dishRepository.save(Dish.builder().name(name + " " + UUID.randomUUID())
                .category(DishCategory.FOOD).price(new BigDecimal(price)).owner(chef.user()).build());
        availabilityRepository.save(DishAvailability.builder().dish(dish).availableDate(date)
                .availableQuantity(quantityOnDate).available(true).build());
        redis.delete(StockRedisClient.stockKey(dish.getUid(), date));
        return dish;
    }

    protected void selectInCart(Account customer, Dish dish, int quantity) {
        Cart cart = cartService.getOrCreateCart(customer.user());
        cartItemRepository.save(CartItem.builder().cart(cart).dish(dish).deliveryDate(date)
                .quantity(quantity).selected(true).build());
    }

    protected int committed(Dish dish) {
        return availabilityRepository.findByDishAndAvailableDate(dish, date).orElseThrow().getAvailableQuantity();
    }

    protected Long redisCounter(Dish dish) {
        return redis.get(StockRedisClient.stockKey(dish.getUid(), date));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    protected ResultActions call(MockHttpServletRequestBuilder request, Account who) throws Exception {
        return mockMvc.perform(request.header("Authorization", who.bearer()));
    }

    /** POST /api/checkouts/ and return the checkout uid. */
    protected UUID checkout(Account customer) throws Exception {
        JsonNode data = data(call(post("/api/checkouts/"), customer).andExpect(status().isOk()));
        return UUID.fromString(data.get("uid").asString());
    }

    protected List<Order> ordersOf(UUID checkoutUid) {
        return orderRepository.findDetailedByCheckoutUid(checkoutUid);
    }

    protected Order onlyOrder(UUID checkoutUid) {
        List<Order> orders = ordersOf(checkoutUid);
        if (orders.size() != 1) {
            throw new IllegalStateException("expected 1 order, got " + orders.size());
        }
        return orders.get(0);
    }

    protected List<StockReservation> reservationsOf(Order order) {
        List<StockReservation> result = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            reservationRepository.findByOrderItemId(item.getId()).ifPresent(result::add);
        }
        return result;
    }

    protected Order reload(UUID orderUid) {
        return orderRepository.findDetailedByUid(orderUid).orElseThrow();
    }
}
