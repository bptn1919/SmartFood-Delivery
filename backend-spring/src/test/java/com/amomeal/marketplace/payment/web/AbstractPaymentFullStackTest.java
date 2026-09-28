package com.amomeal.marketplace.payment.web;

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
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.payment.config.PaymentProperties;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.entity.PaymentTransactionEvent;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.provider.PayOsSignature;
import com.amomeal.marketplace.payment.repository.InternalWalletRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionEventRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionStateRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionRepository;
import com.amomeal.marketplace.payment.service.PaymentHmac;
import com.amomeal.marketplace.payment.support.FakePayOsServer;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the payment module's full-stack tests: real filter chain, real Postgres
 * + Redis (Testcontainers), and {@link FakePayOsServer} standing in for PayOS over real HTTP.
 * Uses EXACTLY the same context configuration as {@code order}'s full-stack tests, so the
 * Spring context (and its containers) is shared rather than started again.
 *
 * <p>Not a {@code *Test} class itself (abstract; Surefire skips it).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.stock.sweep-enabled=false",
        "app.shipping.ahamove.base-url=http://127.0.0.1:1",
        "app.order.notification.retry-delay-ms=50"
})
public abstract class AbstractPaymentFullStackTest {

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
    @Autowired protected PaymentTransactionRepository paymentRepository;
    @Autowired protected PaymentTransactionStateRepository stateRepository;
    @Autowired protected PaymentTransactionEventRepository eventRepository;
    @Autowired protected InternalWalletRepository walletRepository;
    @Autowired protected WalletTransactionRepository walletTransactionRepository;
    @Autowired protected PaymentProperties paymentProperties;
    @Autowired protected PaymentHmac paymentHmac;
    @Autowired protected JdbcTemplate jdbc;

    protected final FakePayOsServer fakePayOs = FakePayOsServer.get();
    protected final LocalDate date = LocalDate.now().plusDays(1);

    @BeforeEach
    void resetFakePayOs() {
        fakePayOs.reset();
    }

    /** Back to the default (intended behavior, payment open question #1) after tests that pin Django's bug. */
    @AfterEach
    void restoreDefaultMode() {
        paymentProperties.setPreserveDjangoConfirmOutcomeBug(false);
    }

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
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CustomUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        user.setFirstName(prefix);
        user.setLastName("Nguyen");
        user = userRepository.save(user);
        return new Account(objectMapper.readTree(json).get("data").get("access_token").asString(), user);
    }

    /** A CHEF with a ChefProfile and a VERIFIED bank account (required to switch a checkout to PAYOS). */
    protected Account verifiedChef(String prefix) throws Exception {
        Account chef = register(prefix, UserRole.CHEF);
        chefProfileRepository.save(ChefProfile.builder().user(chef.user())
                .kitchenStreet("12 Bếp St").kitchenWard("W1").kitchenDistrict("D1").kitchenCity("HCM")
                .kitchenLatitude(10.77).kitchenLongitude(106.70).build());
        chefPaymentInfoRepository.save(ChefPaymentInfo.builder().user(chef.user()).bankName("VCB").bankCode("970436")
                .bankAccountNumber("0011223344").bankAccountName("CHEF NGUYEN").isVerified(true).build());
        return chef;
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

    protected ResultActions call(MockHttpServletRequestBuilder request, Account who) throws Exception {
        return mockMvc.perform(request.header("Authorization", who.bearer()));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    /** What a PayOS place-order leaves behind. */
    protected record Placed(UUID checkoutUid, UUID orderUid, PaymentTransaction payment, JsonNode response) {
        long orderCode() {
            return payment.getPayosOrderCode();
        }
    }

    /** Cart -> checkout -> PAYOS -> place-order, through the real HTTP endpoints. */
    protected Placed placePayosOrder(Account customer, List<Dish> dishes, int quantityEach) throws Exception {
        for (Dish d : dishes) {
            selectInCart(customer, d, quantityEach);
        }
        JsonNode checkout = data(call(post("/api/checkouts/"), customer).andExpect(status().isOk()));
        UUID checkoutUid = UUID.fromString(checkout.get("uid").asString());
        call(patch("/api/checkouts/" + checkoutUid + "/payment-method").contentType("application/json")
                .content("\"PAYOS\""), customer).andExpect(status().isOk());
        JsonNode placed = data(call(post("/api/checkouts/" + checkoutUid + "/place-order"), customer)
                .andExpect(status().isOk()));
        Order order = orderRepository.findDetailedByCheckoutUid(checkoutUid).getFirst();
        PaymentTransaction payment = paymentRepository.findByCheckoutUid(checkoutUid).orElseThrow();
        return new Placed(checkoutUid, order.getUid(), payment, placed);
    }

    protected Order reload(UUID orderUid) {
        return orderRepository.findDetailedByUid(orderUid).orElseThrow();
    }

    protected List<StockReservation> reservationsOf(UUID orderUid) {
        List<StockReservation> out = new ArrayList<>();
        for (OrderItem item : reload(orderUid).getItems()) {
            reservationRepository.findByOrderItemId(item.getId()).ifPresent(out::add);
        }
        out.sort((a, b) -> Long.compare(a.getOrderItemId(), b.getOrderItemId()));
        return out;
    }

    protected PaymentTransactionState stateOf(PaymentTransaction payment) {
        return stateRepository.findByPaymentTransactionId(payment.getId()).orElseThrow();
    }

    protected List<PaymentTransactionEvent> eventsOf(PaymentTransaction payment) {
        return eventRepository.findByPaymentTransactionIdOrderByCreatedAtAscIdAsc(payment.getId());
    }

    protected BigDecimal walletBalance(Account who) {
        return walletRepository.findByUserId(who.user().getId()).map(w -> w.getBalance()).orElse(BigDecimal.ZERO);
    }

    protected List<WalletTransaction> ledgerOf(Account who, WalletTransactionType type) {
        return walletTransactionRepository.findByUserIdOrderByCreatedAtAscIdAsc(who.user().getId()).stream()
                .filter(t -> t.getTransactionType() == type).toList();
    }

    // ------------------------------------------------------------------ PayOS webhooks

    /** A PayOS webhook body for {@code orderCode}, signed with the configured checksum key (or not). */
    protected Map<String, Object> webhookBody(long orderCode, long amount, boolean paid, String linkStatus, boolean validSignature) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", orderCode);
        data.put("amount", amount);
        data.put("description", "DH " + orderCode);
        data.put("accountNumber", "113366668888");
        data.put("reference", "FT" + orderCode);
        data.put("transactionDateTime", "2026-09-23 10:30:00");
        data.put("currency", "VND");
        data.put("paymentLinkId", "link-" + orderCode);
        data.put("code", paid ? "00" : "01");
        data.put("desc", paid ? "Thành công" : "Thất bại");
        data.put("counterAccountBankId", "");
        data.put("counterAccountName", null);
        data.put("virtualAccountName", "");
        if (linkStatus != null) {
            data.put("status", linkStatus);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", paid ? "00" : "01");
        body.put("desc", paid ? "success" : "failed");
        body.put("success", paid);
        body.put("data", data);
        body.put("signature", validSignature
                ? PayOsSignature.createSignature(data, FakePayOsServer.CHECKSUM_KEY)
                : PayOsSignature.createSignature(data, "attacker-key"));
        return body;
    }

    protected ResultActions postWebhook(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/payment/payos/webhook").contentType("application/json")
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Seed a wallet (directly in SQL) with one RELEASE ledger line, signed/hashed like the app
     * does, i.e. a wallet that passes {@code verify_integrity}. */
    protected void seedIntegralWallet(CustomUser user, String amount) {
        BigDecimal value = new BigDecimal(amount).setScale(2);
        UUID uid = UUID.randomUUID();
        String chain = paymentHmac.walletChainHash(uid, user.getId(), "RELEASE", value, BigDecimal.ZERO,
                value, WalletTransaction.GENESIS_HASH);
        jdbc.update("INSERT INTO internal_wallets (user_id, balance, pending_balance, currency, signature) VALUES (?, ?, 0, 'VND', ?)",
                user.getId(), value, paymentHmac.walletSignature(user.getId(), value, new BigDecimal("0.00")));
        Long txId = jdbc.queryForObject("""
                INSERT INTO wallet_transactions (uid, user_id, transaction_type, amount, reference_id, balance_before,
                                                 balance_after, previous_hash, chain_hash)
                VALUES (?, ?, 'RELEASE', ?, 'seed', 0, ?, ?, ?) RETURNING id
                """, Long.class, uid, user.getId(), value, value, WalletTransaction.GENESIS_HASH, chain);
        jdbc.update("INSERT INTO wallet_transaction_states (wallet_transaction_id, status, description, processed_at) "
                + "VALUES (?, 'SUCCESS', 'seed', now())", txId);
    }
}
