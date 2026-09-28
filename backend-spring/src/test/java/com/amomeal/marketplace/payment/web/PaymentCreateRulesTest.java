package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.service.PaymentService;
import com.amomeal.marketplace.payment.service.PaymentValueError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * create-payment on a checkout that already has a payment — Django backend-edit commit 3fa7cdc
 * ({@code payment/services.py}): PaymentTransaction is append-only (never deleted any more);
 * FAILED → error, PENDING → reused (same PayOS link) unless the method differs, and
 * create_cod_payment refuses a checkout that already has a non-COD payment.
 */
class PaymentCreateRulesTest extends AbstractPaymentFullStackTest {

    @Autowired PaymentService paymentService;

    private UUID checkout(Account customer, Dish dish) throws Exception {
        selectInCart(customer, dish, 1);
        return UUID.fromString(data(call(post("/api/checkouts/"), customer).andExpect(status().isOk()))
                .get("uid").asString());
    }

    private String body(UUID checkoutUid, String method) {
        return "{\"checkout_uid\":\"" + checkoutUid + "\",\"payment_method\":\"" + method + "\"}";
    }

    private long paymentRows(UUID checkoutUid) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE checkout_uid = ?",
                Long.class, checkoutUid);
        return n == null ? 0 : n;
    }

    @Test
    void pendingPayos_isReused_notRecreated() throws Exception {
        Account chef = verifiedChef("crchef1");
        Account customer = customerWithAddress("crcus1");
        UUID co = checkout(customer, dish(chef, "Bánh xèo", "100000", 5));

        JsonNode first = data(call(post("/api/payment/create").contentType("application/json")
                .content(body(co, "PAYOS")), customer).andExpect(status().isOk())).get("data");
        JsonNode second = data(call(post("/api/payment/create").contentType("application/json")
                .content(body(co, "PAYOS")), customer).andExpect(status().isOk())).get("data");

        assertThat(second.get("payment_uid").asString()).isEqualTo(first.get("payment_uid").asString());
        assertThat(second.get("order_code").asLong()).isEqualTo(first.get("order_code").asLong());
        assertThat(second.get("payment_url").asString()).isEqualTo(first.get("payment_url").asString());
        assertThat(second.get("status").asString()).isEqualTo("PENDING");
        assertThat(paymentRows(co)).isEqualTo(1);
        PaymentTransaction payment = paymentRepository.findByCheckoutUid(co).orElseThrow();
        assertThat(eventsOf(payment).stream().filter(e -> "PAYOS_CREATE".equals(e.getEventType())).count())
                .isEqualTo(1); // PayOS was called once — no second payment link
    }

    @Test
    void pendingPayos_thenCod_isRejected_andThePendingPaymentIsKept() throws Exception {
        Account chef = verifiedChef("crchef2");
        Account customer = customerWithAddress("crcus2");
        UUID co = checkout(customer, dish(chef, "Bánh cuốn", "100000", 5));
        call(post("/api/payment/create").contentType("application/json").content(body(co, "PAYOS")), customer)
                .andExpect(jsonPath("$.data.success").value(true));

        call(post("/api/payment/create").contentType("application/json").content(body(co, "COD")), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("A payment transaction already exists for another payment method."));

        PaymentTransaction kept = paymentRepository.findByCheckoutUid(co).orElseThrow();
        assertThat(kept.getPaymentMethod()).isEqualTo(PaymentMethod.PAYOS);
        assertThat(stateOf(kept).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentRows(co)).isEqualTo(1);
    }

    @Test
    void failedPayment_isNotDeletedOrRecreated_theCustomerMustStartANewCheckout() throws Exception {
        Account chef = verifiedChef("crchef3");
        Account customer = customerWithAddress("crcus3");
        UUID co = checkout(customer, dish(chef, "Bánh bèo", "100000", 5));

        fakePayOs.setFailCreate(true);
        call(post("/api/payment/create").contentType("application/json").content(body(co, "PAYOS")), customer)
                .andExpect(jsonPath("$.data.success").value(false));
        PaymentTransaction failed = paymentRepository.findByCheckoutUid(co).orElseThrow();
        assertThat(stateOf(failed).getStatus()).isEqualTo(PaymentStatus.FAILED);

        fakePayOs.setFailCreate(false);
        for (String method : new String[]{"PAYOS", "COD"}) {
            call(post("/api/payment/create").contentType("application/json").content(body(co, method)), customer)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(false))
                    .andExpect(jsonPath("$.data.message")
                            .value("This payment transaction has failed. Please create a new checkout."));
        }
        // Append-only: the FAILED row is still the one and only payment of this checkout.
        assertThat(paymentRows(co)).isEqualTo(1);
        assertThat(paymentRepository.findByCheckoutUid(co).orElseThrow().getUid()).isEqualTo(failed.getUid());
        assertThat(stateOf(failed).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void createCodPayment_refusesACheckoutWithANonCodPayment_butReturnsAnExistingCodOne() throws Exception {
        Account chef = verifiedChef("crchef4");
        Account customer = customerWithAddress("crcus4");
        UUID payosCheckout = checkout(customer, dish(chef, "Bánh mì", "100000", 5));
        call(post("/api/payment/create").contentType("application/json").content(body(payosCheckout, "PAYOS")), customer)
                .andExpect(jsonPath("$.data.success").value(true));

        assertThatThrownBy(() -> paymentService.createCodPayment(payosCheckout))
                .isInstanceOf(PaymentValueError.class)
                .hasMessage("A non-COD payment transaction already exists for this checkout.");
        assertThat(paymentRows(payosCheckout)).isEqualTo(1);

        Account customer2 = customerWithAddress("crcus5");
        UUID codCheckout = checkout(customer2, dish(chef, "Bánh chưng", "100000", 5));
        PaymentTransaction cod = paymentService.createCodPayment(codCheckout);
        assertThat(paymentService.createCodPayment(codCheckout).getUid()).isEqualTo(cod.getUid());
        assertThat(paymentRows(codCheckout)).isEqualTo(1);
    }
}
