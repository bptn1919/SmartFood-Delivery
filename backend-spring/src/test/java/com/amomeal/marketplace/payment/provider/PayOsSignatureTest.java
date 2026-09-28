package com.amomeal.marketplace.payment.provider;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PayOS signing, pinned against vectors computed by running the ORIGINAL Python code in
 * ../backend/venv:
 * <ul>
 *   <li>V1–V3: {@code payment.providers.payos.PayOSPaymentProvider._create_signature} (Django's
 *       own hand-written signer — payment-link requests and webhook verification);</li>
 *   <li>V4–V5: {@code payos._crypto.provider.CryptoProvider().create_signature} from the
 *       installed {@code payos==1.1.0} SDK (payout request header / response verification).</li>
 * </ul>
 * If any of these fail, the Java port would reject real PayOS webhooks or have its payouts
 * rejected by PayOS.
 */
class PayOsSignatureTest {

    private static final String KEY = "checksum-test-key";

    @Test
    void paymentRequestSignature_usesOnlyTheFiveDocumentedFields_inSortedOrder() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", 123456);
        data.put("amount", 250000);
        data.put("description", "DH 123456");
        data.put("cancelUrl", "https://fe.test/cancel");
        data.put("returnUrl", "https://fe.test/return");
        data.put("extra", "ignored"); // not part of the 5-field signature
        // V1 (Python): p._create_signature(d1, 'checksum-test-key')
        assertThat(PayOsSignature.createSignature(data, KEY))
                .isEqualTo("08e39421432a08bebc6e68868a4777fb824bb50cba9ef21783b2193137f844bf");
        // and it matches the plain documented formula
        assertThat(PayOsSignature.createSignature(data, KEY)).isEqualTo(PayOsSignature.hmacSha256Hex(KEY,
                "amount=250000&cancelUrl=https://fe.test/cancel&description=DH 123456&orderCode=123456"
                        + "&returnUrl=https://fe.test/return"));
    }

    @Test
    void webhookSignature_signsEveryDataField_nullsBecomeEmpty_unicodeUtf8() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("orderCode", 123);
        d.put("amount", 3000);
        d.put("description", "VQRIO123");
        d.put("accountNumber", "12345678");
        d.put("reference", "TF230204212323");
        d.put("transactionDateTime", "2023-02-04 18:25:00");
        d.put("currency", "VND");
        d.put("paymentLinkId", "124c33293c43417ab7879e14c8d9eb18");
        d.put("code", "00");
        d.put("desc", "Thành công");
        d.put("counterAccountBankId", "");
        d.put("counterAccountBankName", "");
        d.put("counterAccountName", null);
        d.put("counterAccountNumber", null);
        d.put("virtualAccountName", "");
        d.put("virtualAccountNumber", "");
        // V2 (Python): p._create_signature(d2) with PAYOS_CHECKSUM_KEY=checksum-test-key
        assertThat(PayOsSignature.createSignature(d, KEY))
                .isEqualTo("bb4a93f3354c72cba1ded7aa4a32217c57adc6a30d4b130e655dea73f2940e7d");
    }

    @Test
    void webhookSignature_preservesPythonStrQuirks_forBoolFloatNestedAndNullStrings() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("orderCode", 1);
        d.put("a", true);          // Python str(True) -> "True" (the SDK would send "true")
        d.put("b", 1.5);
        d.put("c", "null");        // -> ""
        d.put("d", "undefined");   // -> ""
        d.put("e", 10000000.0);    // Python repr -> "10000000.0" (Java would print 1.0E7)
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("x", "y");
        d.put("f", List.of(1, inner)); // Python repr: [1, {'x': 'y'}]
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("k", null);
        d.put("g", g);             // {'k': None}
        // V3 (Python); canonical string: a=True&b=1.5&c=&d=&e=10000000.0&f=[1, {'x': 'y'}]&g={'k': None}&orderCode=1
        assertThat(PayOsSignature.createSignature(d, KEY))
                .isEqualTo("709f57bc8b7125ac18acff3055beab28d99422a88bd2f243c762a2dc4f03bc63");
        assertThat(PayOsSignature.createSignature(d, KEY)).isEqualTo(PayOsSignature.hmacSha256Hex(KEY,
                "a=True&b=1.5&c=&d=&e=10000000.0&f=[1, {'x': 'y'}]&g={'k': None}&orderCode=1"));
    }

    @Test
    void webhookMode_isUsedUnlessBothOrderCodeAndCancelUrlArePresent() {
        Map<String, Object> onlyOrderCode = new LinkedHashMap<>();
        onlyOrderCode.put("orderCode", 1);
        onlyOrderCode.put("amount", 2);
        assertThat(PayOsSignature.createSignature(onlyOrderCode, KEY))
                .isEqualTo(PayOsSignature.hmacSha256Hex(KEY, "amount=2&orderCode=1"));
        onlyOrderCode.put("cancelUrl", "c");
        // now 5-field mode: missing description/returnUrl sign as empty (Python data.get -> None -> "")
        assertThat(PayOsSignature.createSignature(onlyOrderCode, KEY))
                .isEqualTo(PayOsSignature.hmacSha256Hex(KEY, "amount=2&cancelUrl=c&description=&orderCode=1&returnUrl="));
    }

    @Test
    void sdkPayoutRequestSignature_matchesThePayosSdk() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceId", "wallet_withdraw_5_1700000000");
        body.put("amount", 100000);
        body.put("description", "Wallet withdrawal");
        body.put("toBin", "970436");
        body.put("toAccountNumber", "0123456789");
        body.put("category", List.of("wallet_withdrawal"));
        // V4 (Python): CryptoProvider().create_signature('payout-key', body)
        assertThat(PayOsSignature.sdkHeaderSignature(body, "payout-key"))
                .isEqualTo("5aefb7be2fb3339773319205ceec8f501f8ca13f9eb814d8abe790a4dfb375fa");
    }

    @Test
    void sdkResponseSignature_deepSortsNestedObjects_jsonEncodesLists_andUrlQuotes() {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", "t1");
        tx.put("referenceId", "r1");
        tx.put("amount", 100000);
        tx.put("description", "Wallet withdrawal");
        tx.put("toBin", "970436");
        tx.put("toAccountNumber", "0123456789");
        tx.put("toAccountName", "NGUYỄN VĂN A");
        tx.put("reference", null);
        tx.put("transactionDatetime", null);
        tx.put("errorMessage", null);
        tx.put("errorCode", null);
        tx.put("state", "RECEIVED");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "po_1");
        data.put("referenceId", "r1");
        data.put("transactions", List.of(tx));
        data.put("category", List.of("wallet_withdrawal"));
        data.put("approvalState", "APPROVED");
        data.put("createdAt", "2026-09-23T10:00:00+07:00");
        // V5 (Python)
        assertThat(PayOsSignature.sdkHeaderSignature(data, "payout-key"))
                .isEqualTo("ccd56c49eef6bf1b94b79600672c557a0e45033a057879b96e794a03085490d2");
    }

    @Test
    void emptyHmacKey_behavesLikePythonsEmptyBytesKey() {
        // Python: hmac.new(b'', b'5:180000:0', sha256).hexdigest()  (V6a) — Django's default
        // WALLET_CHAIN_SECRET is "", which Java's SecretKeySpec would reject outright.
        assertThat(PayOsSignature.hmacSha256Hex("", "5:180000:0"))
                .isEqualTo("ded989bbcb49a738407c9aee7a88279fdf5d52a68c64435b91844581a9212561");
        assertThat(PayOsSignature.hmacSha256Hex(null, "5:180000:0"))
                .isEqualTo(PayOsSignature.hmacSha256Hex("", "5:180000:0"));
    }

    @Test
    void constantTimeEquals() {
        assertThat(PayOsSignature.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(PayOsSignature.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(PayOsSignature.constantTimeEquals("abc", "abcd")).isFalse();
    }
}
