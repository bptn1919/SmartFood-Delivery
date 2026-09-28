package com.amomeal.marketplace.payment.provider;

import com.amomeal.marketplace.payment.config.PayOsProperties;
import com.amomeal.marketplace.payment.support.FakePayOsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real {@link PayOsProvider} HTTP code against {@link FakePayOsServer} (a real HTTP server
 * that validates the request signatures and signs its payout responses like PayOS does).
 * No Spring context, no network beyond 127.0.0.1.
 */
class PayOsProviderTest {

    private final FakePayOsServer fake = FakePayOsServer.get();
    private final ObjectMapper json = JsonMapper.builder().build();
    private PayOsProvider provider;
    private PayOsProperties props;

    @BeforeEach
    void setUp() {
        fake.reset();
        props = new PayOsProperties();
        props.setClientId(FakePayOsServer.CLIENT_ID);
        props.setApiKey(FakePayOsServer.API_KEY);
        props.setChecksumKey(FakePayOsServer.CHECKSUM_KEY);
        props.setApiUrl(fake.baseUrl());
        props.setSdkBaseUrl(fake.baseUrl());
        props.setReturnUrl("https://fe.test/return");
        props.setCancelUrl("https://fe.test/cancel");
        props.setSdkRetryBaseDelayMs(1);
        provider = new PayOsProvider(props, json);
    }

    @Test
    void createPayment_signsTheFiveFields_andMapsTheResponseToDjangosKeys() {
        Map<String, Object> r = provider.createPayment(987654321L, 250000, "DH 987654321");
        assertThat(r).containsEntry("success", true)
                .containsEntry("checkout_url", "https://pay.payos.test/web/link-987654321")
                .containsEntry("payment_link_id", "link-987654321")
                .containsEntry("status", "PENDING")
                .containsEntry("account_name", "AMOMEAL TEST")
                .containsKey("qr_code");
        FakePayOsServer.Recorded sent = fake.requestsTo("/v2/payment-requests").getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.headers()).containsEntry("x-client-id", FakePayOsServer.CLIENT_ID)
                .containsEntry("x-api-key", FakePayOsServer.API_KEY);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(sent.body(), Map.class);
        assertThat(body).containsEntry("orderCode", 987654321).containsEntry("amount", 250000)
                .containsEntry("cancelUrl", "https://fe.test/cancel").containsEntry("returnUrl", "https://fe.test/return");
        assertThat(body.get("signature")).isEqualTo(PayOsSignature.hmacSha256Hex(FakePayOsServer.CHECKSUM_KEY,
                "amount=250000&cancelUrl=https://fe.test/cancel&description=DH 987654321&orderCode=987654321"
                        + "&returnUrl=https://fe.test/return"));
    }

    @Test
    void createPayment_gatewayErrorCode_isAFailureDict_notAnException() {
        fake.setFailCreate(true);
        assertThat(provider.createPayment(1L, 1000, "DH 1"))
                .containsEntry("success", false).containsEntry("error", "Forced failure").containsEntry("code", "20");
    }

    @Test
    void createPayment_wrongChecksumKey_isRejectedByPayos() {
        props.setChecksumKey("wrong-key");
        assertThat(provider.createPayment(1L, 1000, "DH 1")).containsEntry("success", false)
                .containsEntry("error", "Invalid signature");
    }

    @Test
    void httpErrors_becomeRequestsStyleFailureDicts() {
        props.setApiKey("bad-key"); // fake answers 401
        Map<String, Object> r = provider.getPaymentInfo(5L);
        assertThat(r).containsEntry("success", false);
        assertThat((String) r.get("error")).startsWith("401 Client Error: Unauthorized for url: ");
        props.setApiUrl("http://127.0.0.1:1"); // connection refused
        assertThat(provider.cancelPayment(5L, "x")).containsEntry("success", false).containsKey("error");
    }

    @Test
    void missingCredentials_failLikeDjangosConstructorCheck() {
        props.setClientId("");
        assertThatThrownBy(() -> provider.createPayment(1L, 1, "d"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing PayOS configuration. Check environment variables.");
    }

    @Test
    void paymentInfo_cancel_invoices() {
        fake.setLinkStatus(77L, "PAID");
        assertThat(provider.getPaymentInfo(77L)).containsEntry("success", true).containsEntry("status", "PAID")
                .containsEntry("order_code", 77).containsEntry("payment_link_id", "link-77");
        assertThat(provider.cancelPayment(77L, "changed my mind")).containsEntry("success", true)
                .containsEntry("status", "CANCELLED");
        assertThat(fake.requestsTo("/v2/payment-requests/77/cancel").getFirst().body())
                .isEqualTo("{\"cancellationReason\":\"changed my mind\"}");
        assertThat(provider.getPaymentInvoices(77L)).containsEntry("success", true);
    }

    // ------------------------------------------------------------------ webhook verification

    private Map<String, Object> webhook(Map<String, Object> data, String signature) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", "00");
        w.put("desc", "success");
        w.put("success", true);
        w.put("data", data);
        w.put("signature", signature);
        return w;
    }

    private static Map<String, Object> paidData(long orderCode) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("orderCode", orderCode);
        d.put("amount", 3000);
        d.put("description", "DH " + orderCode);
        d.put("reference", "TF1");
        d.put("paymentLinkId", "link-" + orderCode);
        d.put("code", "00");
        d.put("desc", "Thành công");
        d.put("counterAccountName", null);
        return d;
    }

    @Test
    void verifyWebhook_validSignature_isValidAndSuccessful_caseInsensitive() {
        Map<String, Object> d = paidData(42L);
        String sig = PayOsSignature.createSignature(d, FakePayOsServer.CHECKSUM_KEY);
        Map<String, Object> v = provider.verifyWebhookData(webhook(d, sig.toUpperCase()));
        assertThat(v).containsEntry("is_valid", true).containsEntry("is_success", true)
                .containsEntry("order_code", 42L).containsEntry("reference", "TF1")
                .containsEntry("payment_link_id", "link-42");
    }

    @Test
    void verifyWebhook_tamperedAmount_orWrongKey_isInvalid() {
        Map<String, Object> d = paidData(42L);
        String sig = PayOsSignature.createSignature(d, FakePayOsServer.CHECKSUM_KEY);
        d.put("amount", 1); // attacker lowers the paid amount after signing
        assertThat(provider.verifyWebhookData(webhook(d, sig))).containsEntry("is_valid", false)
                .containsEntry("order_code", 42L);
        Map<String, Object> d2 = paidData(43L);
        String forged = PayOsSignature.createSignature(d2, "attacker-key");
        assertThat(provider.verifyWebhookData(webhook(d2, forged))).containsEntry("is_valid", false);
    }

    @Test
    void verifyWebhook_successNeedsAllThreeCodes_andPythonEqualsTrue() {
        Map<String, Object> d = paidData(44L);
        d.put("code", "01");
        String sig = PayOsSignature.createSignature(d, FakePayOsServer.CHECKSUM_KEY);
        assertThat(provider.verifyWebhookData(webhook(d, sig))).containsEntry("is_valid", true)
                .containsEntry("is_success", false);
        Map<String, Object> d2 = paidData(45L);
        Map<String, Object> w = webhook(d2, PayOsSignature.createSignature(d2, FakePayOsServer.CHECKSUM_KEY));
        w.put("success", 1); // Python: 1 == True
        assertThat(provider.verifyWebhookData(w)).containsEntry("is_success", true);
    }

    @Test
    void verifyWebhook_malformedPayloads_areInvalid_neverThrow() {
        assertThat(provider.verifyWebhookData(List.of(1, 2))).containsEntry("is_valid", false).containsEntry("code", "99");
        Map<String, Object> noData = webhook(Map.of(), "x");
        assertThat(provider.verifyWebhookData(noData)).containsEntry("is_valid", false)
                .containsEntry("desc", "Missing data field");
        Map<String, Object> d = paidData(46L);
        Map<String, Object> nullSig = webhook(d, null);
        assertThat(provider.verifyWebhookData(nullSig)).containsEntry("is_valid", false);
        Map<String, Object> listData = webhook(null, "x");
        listData.put("data", List.of("a"));
        assertThat(provider.verifyWebhookData(listData)).containsEntry("is_valid", false);
    }

    // ------------------------------------------------------------------ payouts (SDK emulation)

    @Test
    void createPayout_signsTheRequestHeader_verifiesTheResponseSignature_andSendsTheIdempotencyKey() {
        Map<String, Object> r = provider.createPayout("ref-1", 150000, "Wallet withdrawal but a long description",
                "970436", "0123456789", "idem-1", List.of("wallet_withdrawal"));
        assertThat(r).containsEntry("success", true).containsEntry("payout_id", "po-ref-1")
                .containsEntry("reference_id", "ref-1").containsEntry("approval_state", "APPROVED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txs = (List<Map<String, Object>>) r.get("transactions");
        assertThat(txs.getFirst()).containsEntry("to_bin", "970436").containsEntry("state", "RECEIVED");
        FakePayOsServer.Recorded sent = fake.requestsTo("/v1/payouts").getFirst();
        assertThat(sent.headers()).containsEntry("x-idempotency-key", "idem-1").containsKey("x-signature");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(sent.body(), Map.class);
        assertThat(body.get("description")).isEqualTo("Wallet withdrawal but a l"); // truncated to 25 chars
        assertThat(body.keySet()).containsExactly("referenceId", "amount", "description", "toBin", "toAccountNumber", "category");
    }

    @Test
    void createPayout_5xx_isRetriedByTheSdkLayer_thenReportedWithStatusCode() {
        fake.setPayoutMode("http500");
        Map<String, Object> r = provider.createPayout("ref-2", 1000, "d", "970436", "1", "k", List.of("c"));
        assertThat(r).containsEntry("success", false).containsEntry("status_code", 500).containsEntry("error", "Internal error");
        assertThat(fake.payoutCalls()).isEqualTo(3); // 1 + DEFAULT_MAX_RETRIES(2)
    }

    @Test
    void createPayout_4xx_isNotRetried() {
        fake.setPayoutMode("http400");
        Map<String, Object> r = provider.createPayout("ref-3", 1000, "d", "970436", "1", "k", List.of("c"));
        assertThat(r).containsEntry("success", false).containsEntry("status_code", 400)
                .containsEntry("error", "Tai khoan khong hop le");
        assertThat(fake.payoutCalls()).isEqualTo(1);
    }

    @Test
    void createPayout_forgedOrMissingResponseSignature_isRejected() {
        fake.setPayoutMode("badsig");
        assertThat(provider.createPayout("ref-4", 1000, "d", "970436", "1", "k", List.of("c")))
                .containsEntry("success", false).containsEntry("error", "Response signature verification failed");
        fake.setPayoutMode("nosig");
        assertThat(provider.createPayout("ref-5", 1000, "d", "970436", "1", "k", List.of("c")))
                .containsEntry("success", false).containsEntry("error", "Response signature missing");
        fake.setPayoutMode("code01");
        assertThat(provider.createPayout("ref-6", 1000, "d", "970436", "1", "k", List.of("c")))
                .containsEntry("success", false).containsEntry("error", "Payout rejected");
    }

    @Test
    void createPayout_missingBin_isAValidationFailure_beforeAnyHttpCall() {
        assertThat(provider.createPayout("ref-7", 1000, "d", null, "1", "k", List.of("c")))
                .containsEntry("success", false).containsEntry("message", "Failed to create payout");
        assertThat(fake.payoutCalls()).isZero();
    }
}
