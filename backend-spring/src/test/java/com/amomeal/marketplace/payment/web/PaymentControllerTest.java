package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.repository.CustomerPaymentInfoRepository;
import com.amomeal.marketplace.payment.service.SettlementService;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.UserOtpRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The {@code /api/payment/*} endpoints over real HTTP (envelope, auth, validation, OTP flows). */
class PaymentControllerTest extends AbstractPaymentFullStackTest {

    @Autowired UserOtpRepository otpRepository;
    @Autowired CustomerPaymentInfoRepository customerPaymentInfoRepository;
    @Autowired SettlementService settlementService;
    @Autowired com.amomeal.marketplace.payment.service.WalletService walletService;

    /** The OTP is emailed, only its Argon2 hash is stored — so tests pin a known code on the record. */
    private void pinOtp(String sessionToken, String code) {
        UserOtp otp = otpRepository.findFirstByResetSessionToken(sessionToken).orElseThrow();
        otp.setOtpHash(new Argon2PasswordEncoder(16, 32, 1, 65536, 2).encode(code));
        otpRepository.save(otp);
    }

    // ------------------------------------------------------------------ bank info (OTP-verified)

    @Test
    void bankInfo_isSavedUnverified_thenVerifiedByOtp_otpIsSingleUse() throws Exception {
        Account customer = register("bank", UserRole.CUSTOMER);
        String body = """
                {"bank_name":"Vietcombank","bank_code":" 970 436 ","bank_account_number":"0123-456 789",
                 "bank_account_name":"  NGUYEN   VAN A ","bank_branch":"HCM"}
                """;
        JsonNode session = data(call(post("/api/payment/customer/bank-info").contentType("application/json").content(body),
                customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.message_code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.message").value("OTP sent to your email. Please verify to activate bank account.")));
        String token = session.get("reset_session_token").asString();

        CustomerPaymentInfo saved = customerPaymentInfoRepository.findByUserId(customer.user().getId()).orElseThrow();
        assertThat(saved.isVerified()).isFalse();
        assertThat(saved.getBankCode()).isEqualTo("970436");                 // normalized like the pydantic validators
        assertThat(saved.getBankAccountNumber()).isEqualTo("0123456789");
        assertThat(saved.getBankAccountName()).isEqualTo("NGUYEN VAN A");

        pinOtp(token, "4321");
        call(post("/api/payment/customer/bank-info/verify-otp").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"0000\"}"), customer)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message_code").value("INVALID_OTP"));
        call(post("/api/payment/customer/bank-info/verify-otp").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"4321\"}"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_verified").value(true))
                .andExpect(jsonPath("$.data.bank_code").value("970436"));
        // replay of the consumed OTP
        call(post("/api/payment/customer/bank-info/verify-otp").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"4321\"}"), customer)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message_code").value("INVALID_OR_EXPIRED_TOKEN"));

        call(get("/api/payment/customer/bank-info"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bank_name").value("Vietcombank"))
                .andExpect(jsonPath("$.data.is_verified").value(true))
                .andExpect(jsonPath("$.data.verified_at").isNotEmpty());
    }

    @Test
    void bankInfo_validation_andSomeoneElsesOtp() throws Exception {
        Account customer = register("bankval", UserRole.CUSTOMER);
        call(post("/api/payment/customer/bank-info").contentType("application/json").content("""
                {"bank_name":"Vietcombank","bank_code":"970436","bank_account_number":"0123456789","bank_account_name":"nguyen van a"}
                """), customer).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        call(post("/api/payment/customer/bank-info").contentType("application/json").content("""
                {"bank_name":"Not A Bank","bank_code":"970436","bank_account_number":"0123456789","bank_account_name":"A"}
                """), customer).andExpect(status().isUnauthorized());
        call(get("/api/payment/customer/bank-info"), customer).andExpect(status().isInternalServerError());

        String token = data(call(post("/api/payment/customer/bank-info").contentType("application/json").content("""
                {"bank_name":"VCB","bank_code":"970436","bank_account_number":"0123456789","bank_account_name":"A"}
                """), customer).andExpect(status().isOk())).get("reset_session_token").asString();
        pinOtp(token, "1111");
        Account intruder = register("intruder", UserRole.CUSTOMER);
        call(post("/api/payment/customer/bank-info/verify-otp").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"1111\"}"), intruder)
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ withdrawal (OTP-confirmed payout)

    private void verifiedBank(Account who) {
        customerPaymentInfoRepository.save(CustomerPaymentInfo.builder().userId(who.user().getId())
                .bankName("Vietcombank").bankCode("970436").bankAccountNumber("0123456789")
                .bankAccountName("NGUYEN VAN A").verified(true).build());
    }

    private String requestWithdraw(Account who, String amount) throws Exception {
        return data(call(post("/api/payment/wallet/me/withdraw").contentType("application/json")
                .content("{\"amount\":" + amount + "}"), who).andExpect(status().isOk()))
                .get("reset_session_token").asString();
    }

    @Test
    void withdraw_requestOtp_thenConfirm_paysOutAndDebitsTheWallet_withTheAmountFixedAtRequestTime() throws Exception {
        Account user = register("wdhttp", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "100000");

        String token = requestWithdraw(user, "30000.4");
        UserOtp otp = otpRepository.findFirstByResetSessionToken(token).orElseThrow();
        assertThat(otp.getTargetEmail()).isEqualTo("30000"); // the quantized amount rides on the OTP row
        pinOtp(token, "2468");

        call(post("/api/payment/wallet/me/withdraw/confirm").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"2468\"}"), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.amount").value(30000.0))
                .andExpect(jsonPath("$.data.message").value("Withdrawal processed successfully"))
                .andExpect(jsonPath("$.data.bank_account").value("Vietcombank - ******6789"));
        assertThat(walletBalance(user)).isEqualByComparingTo("70000");
        assertThat(fakePayOs.payoutCalls()).isEqualTo(1);

        // the consumed OTP cannot trigger a second payout
        call(post("/api/payment/wallet/me/withdraw/confirm").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"2468\"}"), user)
                .andExpect(status().isUnauthorized());
        assertThat(fakePayOs.payoutCalls()).isEqualTo(1);
        assertThat(walletBalance(user)).isEqualByComparingTo("70000");
    }

    @Test
    void withdraw_otpBruteForce_isCappedAtThreeAttempts() throws Exception {
        Account user = register("wdbrute", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "100000");
        String token = requestWithdraw(user, "20000");
        pinOtp(token, "9999");
        for (int i = 0; i < 3; i++) {
            call(post("/api/payment/wallet/me/withdraw/confirm").contentType("application/json")
                    .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"000" + i + "\"}"), user)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message_code").value("INVALID_OTP"));
        }
        // 4th try, even with the right code: Django's UserOTP.verify raises ValueError -> 500
        call(post("/api/payment/wallet/me/withdraw/confirm").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"9999\"}"), user)
                .andExpect(status().isInternalServerError());
        assertThat(fakePayOs.payoutCalls()).isZero();
        assertThat(walletBalance(user)).isEqualByComparingTo("100000");
    }

    @Test
    void withdraw_requestValidation_inDjangosOrder() throws Exception {
        Account user = register("wdval", UserRole.CUSTOMER);
        String url = "/api/payment/wallet/me/withdraw";
        call(post(url).contentType("application/json").content("{\"amount\":0}"), user)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message_code").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.data").value("Withdrawal amount must be greater than zero"));
        call(post(url).contentType("application/json").content("{\"amount\":5000}"), user)
                .andExpect(jsonPath("$.data").value("Minimum withdrawal amount is 10,000 VND"));
        call(post(url).contentType("application/json").content("{\"amount\":20000001}"), user)
                .andExpect(jsonPath("$.data").value("Daily withdrawal limit exceeded. Remaining today: 20,000,000 VND"));
        call(post(url).contentType("application/json").content("{\"amount\":20000}"), user)
                .andExpect(jsonPath("$.data").value("Bank information not found. Please add bank information first."));
        customerPaymentInfoRepository.save(CustomerPaymentInfo.builder().userId(user.user().getId())
                .bankName("VCB").bankCode("970436").bankAccountNumber("0123456789").bankAccountName("A")
                .verified(false).build());
        call(post(url).contentType("application/json").content("{\"amount\":20000}"), user)
                .andExpect(jsonPath("$.data").value("Bank information is not verified"));
        call(post(url).contentType("application/json").content("{}"), user)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
    }

    /**
     * FIXED DJANGO BUG (open question #2): Django answered "Wallet integrity check failed" here
     * (a new wallet had an empty signature). A new wallet is now signed at creation, so the
     * request reaches the real balance check.
     */
    @Test
    void withdraw_fromABrandNewWallet_passesIntegrity_andFailsOnlyOnTheBalance() throws Exception {
        Account user = register("wdreal", UserRole.CUSTOMER);
        verifiedBank(user);
        call(post("/api/payment/wallet/me/withdraw").contentType("application/json").content("{\"amount\":20000}"), user)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").value("Insufficient internal wallet balance"));
    }

    /**
     * The whole money path end to end, over real HTTP, with NO seeded wallet: a chef earns through
     * a completed PayOS order (webhook → escrow → complete → 90% RELEASE), then withdraws part of
     * it through the OTP flow (signed PayOS payout), and the wallet still verifies afterwards
     * (signature, ledger sum, hash chain) — impossible in Django (open questions #1 and #2).
     */
    @Test
    void chefEarnsThroughACompletedOrder_thenWithdraws_andTheWalletStillVerifies() throws Exception {
        Account chef = verifiedChef("earnchef");
        Account customer = customerWithAddress("earncus");
        Dish dish = dish(chef, "Cơm tấm", "100000", 5);
        Placed placed = placePayosOrder(customer, java.util.List.of(dish), 1);
        var order = reload(placed.orderUid());
        assertThat(order.getTotalPrice()).isEqualByComparingTo("140000"); // 100k + 10% tax + 30k ship
        postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true))
                .andExpect(status().isOk());
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        for (String step : java.util.List.of("confirm", "start-processing", "start-delivery", "complete")) {
            call(post("/api/chef/orders/" + placed.orderUid() + "/" + step), chef).andExpect(status().isOk());
        }
        assertThat(walletBalance(chef)).isEqualByComparingTo("126000"); // 90% of 140000
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.RELEASED);

        String token = requestWithdraw(chef, "100000");
        pinOtp(token, "1357");
        call(post("/api/payment/wallet/me/withdraw/confirm").contentType("application/json")
                .content("{\"reset_session_token\":\"" + token + "\",\"otp\":\"1357\"}"), chef)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.amount").value(100000.0))
                .andExpect(jsonPath("$.data.bank_account").value("VCB - ******3344"));
        assertThat(fakePayOs.payoutCalls()).isEqualTo(1);
        assertThat(fakePayOs.requestsTo("/v1/payouts").getFirst().body()).contains("\"amount\":100000")
                .contains("\"toAccountNumber\":\"0011223344\"");

        var wallet = walletRepository.findByUserId(chef.user().getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("26000");
        assertThat(wallet.getPendingBalance()).isEqualByComparingTo("0");
        var integrity = walletService.verifyIntegrity(walletService.getOrCreateWallet(chef.user().getId()));
        assertThat(integrity.signatureValid()).isTrue();
        assertThat(integrity.balanceMatchesLedger()).isTrue();
        assertThat(integrity.chainIntact()).isTrue();
        assertThat(integrity.chainLength()).isEqualTo(2); // RELEASE, PAYOUT
        assertThat(walletService.computeExpectedBalance(chef.user().getId())).isEqualByComparingTo("26000");

        // the wallet is not locked after a payout: the rest can be requested too
        requestWithdraw(chef, "20000");
    }

    // ------------------------------------------------------------------ payment endpoints

    private UUID codCheckout(Account customer, Dish dish) throws Exception {
        selectInCart(customer, dish, 1);
        return UUID.fromString(data(call(post("/api/checkouts/"), customer).andExpect(status().isOk())).get("uid").asString());
    }

    @Test
    void createPayment_cod_thenAlreadyCompleted_andFailuresAreSuccessFalseNot4xx() throws Exception {
        Account chef = verifiedChef("cpchef");
        Account customer = customerWithAddress("cpcus");
        Dish dish = dish(chef, "Cháo", "100000", 5);
        UUID co = codCheckout(customer, dish);

        call(post("/api/payment/create").contentType("application/json")
                .content("{\"checkout_uid\":\"" + co + "\",\"payment_method\":\"COD\"}"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.data.transaction_id").value("COD-" + co))
                .andExpect(jsonPath("$.data.data.amount").value(140000));
        call(post("/api/payment/create").contentType("application/json")
                .content("{\"checkout_uid\":\"" + co + "\",\"payment_method\":\"COD\"}"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("Payment already completed"));
        UUID missing = UUID.randomUUID();
        call(post("/api/payment/create").contentType("application/json")
                .content("{\"checkout_uid\":\"" + missing + "\",\"payment_method\":\"PAYOS\"}"), customer)
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("Checkout " + missing + " not found"));
    }

    @Test
    void createPayment_payos_thenStatus_info_invoices_andTheInvertedCancelGuard() throws Exception {
        Account chef = verifiedChef("ppchef");
        Account customer = customerWithAddress("ppcus");
        Dish dish = dish(chef, "Hủ tiếu", "100000", 5);
        UUID co = codCheckout(customer, dish);

        JsonNode created = data(call(post("/api/payment/create").contentType("application/json")
                .content("{\"checkout_uid\":\"" + co + "\",\"payment_method\":\"PAYOS\",\"buyer_name\":\"ignored\"}"), customer)
                .andExpect(status().isOk())).get("data");
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        long orderCode = created.get("order_code").asLong();
        assertThat(created.get("payment_url").asString()).endsWith("/web/link-" + orderCode);
        assertThat(created.get("payment_link_id").asString()).isEqualTo("link-" + orderCode);
        assertThat(created.get("transaction_id").asString()).isEqualTo("link-" + orderCode);
        assertThat(created.get("account_name").asString()).isEqualTo("AMOMEAL TEST");
        String paymentUid = created.get("payment_uid").asString();

        call(get("/api/payment/" + paymentUid + "/status"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.transaction_id").value("link-" + orderCode));
        call(get("/api/payment/order/" + orderCode + "/info"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.order_code").value(orderCode));
        call(get("/api/payment/" + paymentUid + "/invoices"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoices[0].invoiceId").value("inv-1"))
                .andExpect(jsonPath("$.data.invoices[0].codeOfTax").value("CT1"));

        // PRESERVED Django bug: an unpaid (PENDING) payment can NOT be cancelled through this endpoint
        call(post("/api/payment/" + paymentUid + "/cancel").contentType("application/json")
                .content("{\"cancellation_reason\":\"no\"}"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.error").value("Cannot cancel successful payment"));
        // ... while a HOLDING one "can": it becomes REFUND_PENDING and no money moves
        var payment = paymentRepository.findByUid(UUID.fromString(paymentUid)).orElseThrow();
        jdbc.update("UPDATE payment_transaction_states SET status = 'HOLDING' WHERE payment_transaction_id = ?", payment.getId());
        call(post("/api/payment/" + paymentUid + "/cancel").contentType("application/json")
                .content("{\"cancellation_reason\":\"no\"}"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("REFUND_PENDING"));
        assertThat(stateOf(payment).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");

        call(get("/api/payment/" + UUID.randomUUID() + "/status"), customer).andExpect(status().isInternalServerError());
    }

    // ------------------------------------------------------------------ chef balance / COD settlement

    @Test
    void chefBalance_andCodSettlement_onlyThatChefOrAdmin() throws Exception {
        Account chef = verifiedChef("balchef");
        Account viewer = register("viewer", UserRole.CUSTOMER);
        Account admin = register("baladmin", UserRole.ADMIN);
        Account otherChef = verifiedChef("balotherchef");

        call(get("/api/payment/chef/" + viewer.user().getId() + "/balance"), viewer)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.data").value("Chef not found or not a chef"));
        // Post-port authorization fix (2026-09-25): another user / another chef gets 403; anonymous 401
        for (Account intruder : new Account[]{viewer, otherChef}) {
            call(get("/api/payment/chef/" + chef.user().getId() + "/balance"), intruder).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));
            call(post("/api/payment/chef/" + chef.user().getId() + "/cod/settle"), intruder).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/payment/chef/" + chef.user().getId() + "/balance")).andExpect(status().isUnauthorized());
        call(get("/api/payment/chef/" + chef.user().getId() + "/balance"), admin).andExpect(status().isOk());
        call(get("/api/payment/chef/" + chef.user().getId() + "/balance"), chef).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chef_email").value(chef.user().getEmail()))
                .andExpect(jsonPath("$.data.cod_balance.unsettled_orders").value(0))
                .andExpect(jsonPath("$.data.payos_balance.note").value("Internal wallet available balance"))
                .andExpect(jsonPath("$.data.currency").value("VND"));

        call(post("/api/payment/chef/" + chef.user().getId() + "/cod/settle"), chef).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("COD balance not found for chef"))
                .andExpect(jsonPath("$.data.transactions").isEmpty());

        settlementService.updateChefCodBalance(chef.user().getId(), new BigDecimal("50000"));
        call(post("/api/payment/chef/" + chef.user().getId() + "/cod/settle"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.order_count").value(0));
        call(get("/api/payment/chef/" + chef.user().getId() + "/balance"), chef)
                .andExpect(jsonPath("$.data.cod_balance.unsettled_balance").value(0));
        call(post("/api/payment/chef/" + chef.user().getId() + "/cod/settle"), chef)
                .andExpect(jsonPath("$.data.message").value("No unsettled COD balance available"));
    }

    @Test
    void paymentEndpoints_requireTheCheckoutOwner_adminMayRead_anonymousUnauthorized() throws Exception {
        Account chef = verifiedChef("gchef");
        Account owner = customerWithAddress("gowner");
        Account stranger = customerWithAddress("gstranger");
        Account admin = register("gadmin", UserRole.ADMIN);
        Dish dish = dish(chef, "Bun", "100000", 5);
        UUID co = codCheckout(owner, dish);
        String body = "{\"checkout_uid\":\"" + co + "\",\"payment_method\":\"PAYOS\"}";

        // create: only the checkout's owner (and no ADMIN); a refused call creates nothing
        call(post("/api/payment/create").contentType("application/json").content(body), stranger)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message_code").value("HTTP_ERROR"));
        call(post("/api/payment/create").contentType("application/json").content(body), admin)
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/payment/create").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        assertThat(paymentRepository.findByCheckoutUid(co)).isEmpty();

        JsonNode created = data(call(post("/api/payment/create").contentType("application/json").content(body), owner)
                .andExpect(status().isOk())).get("data");
        String paymentUid = created.get("payment_uid").asString();
        long orderCode = created.get("order_code").asLong();

        // read: owner + ADMIN ok, stranger 403
        for (String path : new String[]{"/api/payment/" + paymentUid + "/status",
                "/api/payment/order/" + orderCode + "/info", "/api/payment/" + paymentUid + "/invoices"}) {
            call(get(path), owner).andExpect(status().isOk());
            call(get(path), admin).andExpect(status().isOk());
            call(get(path), stranger).andExpect(status().isForbidden());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        // cancel: owner only (state must not move for a refused caller)
        var payment = paymentRepository.findByUid(UUID.fromString(paymentUid)).orElseThrow();
        jdbc.update("UPDATE payment_transaction_states SET status = 'HOLDING' WHERE payment_transaction_id = ?", payment.getId());
        String cancel = "{\"cancellation_reason\":\"no\"}";
        call(post("/api/payment/" + paymentUid + "/cancel").contentType("application/json").content(cancel), stranger)
                .andExpect(status().isForbidden());
        call(post("/api/payment/" + paymentUid + "/cancel").contentType("application/json").content(cancel), admin)
                .andExpect(status().isForbidden());
        assertThat(stateOf(payment).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        call(post("/api/payment/" + paymentUid + "/cancel").contentType("application/json").content(cancel), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REFUND_PENDING"));
    }
}
