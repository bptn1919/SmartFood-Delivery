package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionStateRepository;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared fixtures for the admin module's full-stack tests (real filter chain, real Postgres + Redis through
 * {@link TestcontainersConfiguration}). Users come from the real {@code /api/auth/register} endpoint with
 * their Django group set through the repository (like every other module's controller test); orders,
 * checkouts, addresses and payment rows are inserted through the owning modules' repositories, and an
 * order's {@code created_at} is back-dated with plain SQL (it is {@code updatable=false} on the entity).
 *
 * <p>The Testcontainers database is shared by every test class, so aggregate assertions are either scoped
 * to a far-past date window nobody else writes to, or are before/after deltas.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractAdminFullStackTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected CustomUserRepository userRepository;
    @Autowired protected CheckoutRepository checkoutRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected OrderItemRepository orderItemRepository;
    @Autowired protected CustomerAddressRepository addressRepository;
    @Autowired protected PaymentTransactionRepository paymentTransactionRepository;
    @Autowired protected PaymentTransactionStateRepository paymentTransactionStateRepository;
    @Autowired protected JdbcTemplate jdbc;

    private static final AtomicLong CHEF_BANK_IDS = new AtomicLong(8_000_000);

    protected record Account(String token, Long userId, String email, String username) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    // ------------------------------------------------------------------ users

    protected Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String username = prefix + "-" + nonce;
        String body = """
                {"username":"%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000099"}
                """.formatted(username, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(json).path("data").path("access_token").asString();
        CustomUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        userRepository.save(user);
        return new Account(token, user.getId(), email, username);
    }

    protected void setName(Account a, String first, String last) {
        CustomUser u = userRepository.findById(a.userId()).orElseThrow();
        u.setFirstName(first);
        u.setLastName(last);
        userRepository.save(u);
    }

    // ------------------------------------------------------------------ orders

    protected CustomerAddress address(Account customer, String district) {
        return addressRepository.save(CustomerAddress.builder()
                .user(userRepository.getReferenceById(customer.userId()))
                .address("12 Nguyen Hue").street("Nguyen Hue").ward("Ben Nghe").district(district).city("HCMC")
                .build());
    }

    protected Checkout checkout(Account customer, CustomerAddress address, PaymentMethod method) {
        return checkoutRepository.save(Checkout.builder()
                .owner(userRepository.getReferenceById(customer.userId()))
                .fullName("Fixture Buyer").phoneNumber("0900000001")
                .deliveryAddress(address).paymentMethod(method)
                .deliveryDate(LocalDate.of(2026, 5, 1)).deliveryTime(LocalTime.of(18, 30))
                .build());
    }

    /** Persists an order (own checkout) and, when {@code createdAt != null}, back-dates {@code created_at}. */
    protected Order order(Account customer, Account chef, CustomerAddress address, PaymentMethod method,
                          OrderStatus status, PaymentStatus payment, String total, Instant createdAt) {
        Checkout c = checkout(customer, address, method);
        return orderOn(c, customer, chef, status, payment, total, createdAt);
    }

    protected Order orderOn(Checkout c, Account customer, Account chef, OrderStatus status, PaymentStatus payment,
                            String total, Instant createdAt) {
        BigDecimal t = new BigDecimal(total);
        Order o = orderRepository.save(Order.builder().checkout(c)
                .owner(userRepository.getReferenceById(customer.userId()))
                .chef(chef == null ? null : userRepository.getReferenceById(chef.userId()))
                .status(status).paymentStatus(payment).subTotal(t).totalPrice(t).build());
        if (createdAt != null) {
            jdbc.update("update \"order\" set created_at = ? where uid = ?",
                    OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), o.getUid());
        }
        return orderRepository.findById(o.getUid()).orElseThrow();
    }

    protected OrderItem item(Order o, String dish, int qty, String price) {
        return orderItemRepository.save(OrderItem.builder().order(o).dishName(dish)
                .dishImageUrl("https://cdn.example.test/" + dish + ".jpg").quantity(qty)
                .price(new BigDecimal(price)).build());
    }

    protected void payment(Checkout c, PaymentMethod method, PaymentStatus status, String amount) {
        PaymentTransaction tx = paymentTransactionRepository.save(PaymentTransaction.builder()
                .checkoutUid(c.getUid()).paymentMethod(method).amount(new BigDecimal(amount)).build());
        paymentTransactionStateRepository.save(PaymentTransactionState.builder()
                .paymentTransactionId(tx.getId()).status(status).build());
    }

    protected long insertChefBank(Account chef, boolean verified, boolean deleted) {
        long id = CHEF_BANK_IDS.incrementAndGet();
        jdbc.update("insert into chef_payment_info (id, user_id, bank_name, bank_code, bank_account_number, "
                        + "bank_account_name, bank_branch, citizen_id, tax_code, is_verified, deleted) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?)", id, chef.userId(), "Vietcombank", "VCB", "00112233",
                "NGUYEN VAN CHEF", "HCM", "079123456789", "0312345678", verified, deleted);
        return id;
    }

    // ------------------------------------------------------------------ http helpers

    protected ResultActions as(Account a, MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(a == null ? b : b.header("Authorization", a.bearer()));
    }

    protected ResultActions getAs(Account a, String url) throws Exception {
        return as(a, get(url));
    }

    protected ResultActions patchAs(Account a, String url) throws Exception {
        return as(a, patch(url));
    }

    protected ResultActions patchJson(Account a, String url, String json) throws Exception {
        return as(a, patch(url).contentType("application/json").content(json));
    }

    protected ResultActions postJson(Account a, String url, String json) throws Exception {
        return as(a, post(url).contentType("application/json").content(json));
    }

    protected JsonNode body(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    protected static String uid() {
        return UUID.randomUUID().toString();
    }
}
