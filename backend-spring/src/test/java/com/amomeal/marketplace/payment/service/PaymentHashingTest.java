package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.config.PaymentProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.amomeal.marketplace.order.entity.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The internal HMACs (wallet signature, wallet ledger chain, payment event chain), the PayOS
 * order-code derivation, the payment-state transition table and the payout helpers — each
 * pinned against the Django source or a vector computed with the original Python.
 */
class PaymentHashingTest {

    private static PaymentHmac hmac(String walletSecret, String eventSecret) {
        PaymentProperties p = new PaymentProperties();
        p.setWalletChainSecret(walletSecret);
        p.setEventSecret(eventSecret);
        return new PaymentHmac(p);
    }

    @Test
    void walletSignature_isHmacOverUserBalancePending_withCanonicalTwoDecimalStrings() {
        // Vectors computed with Python hmac over "5:180000.00:0.00" (canonical 2-dp form, the fix
        // for payment open question #2), under the empty default secret and a real one.
        assertThat(hmac("", "x").walletSignature(5L, new BigDecimal("180000"), BigDecimal.ZERO))
                .isEqualTo("6c27a0aea52ab29ad8b7de7da510051b82f2253b11738376e701f011a657bf77");
        assertThat(hmac("s3cret", "x").walletSignature(5L, new BigDecimal("180000.00"), new BigDecimal("0.00")))
                .isEqualTo("3b9192ee1dbb89c248ef055aaa91225a14b0e05203af90c28d2f7efa10452937");
    }

    @Test
    void fixedDjangoBug_signatureIsScaleInsensitive_writeTimeValuesMatchTheDbReadBack() {
        PaymentHmac h = hmac("s3cret", "x");
        // written at credit time: quantized balance (scale 0) + in-memory 0 pending
        String atWrite = h.walletSignature(5L, new BigDecimal("180000"), BigDecimal.ZERO);
        // recomputed at verify time from NUMERIC(15,2) columns
        String atVerify = h.walletSignature(5L, new BigDecimal("180000.00"), new BigDecimal("0.00"));
        assertThat(atWrite).isEqualTo(atVerify);
        assertThat(h.walletSignature(5L, new BigDecimal("1.8E+5"), new BigDecimal("0.0"))).isEqualTo(atVerify);
        assertThat(PaymentHmac.canonicalAmount(new BigDecimal("1.8E+5"))).isEqualTo("180000.00");
        assertThat(PaymentHmac.canonicalAmount(BigDecimal.ZERO)).isEqualTo("0.00");
    }

    @Test
    void walletChainHash_isHmacOverCanonicalAmounts() {
        UUID uid = UUID.fromString("3f2b1c9e-8d7a-4b6c-9e5f-1a2b3c4d5e6f");
        // Python: f"{uid}:{user_id}:{tx_type}:{amount}:{before}:{after}:{prev}" with 2-dp amounts
        String expected = "8cc5cf5967205bf8e463c73fd9b65d164c6ecd79adcd124410bc5c520f588920";
        assertThat(hmac("s3cret", "x").walletChainHash(uid, 5L, "RELEASE", new BigDecimal("180000"), BigDecimal.ZERO,
                new BigDecimal("180000"), "0".repeat(64))).isEqualTo(expected);
        assertThat(hmac("s3cret", "x").walletChainHash(uid, 5L, "RELEASE", new BigDecimal("180000.00"),
                new BigDecimal("0.00"), new BigDecimal("180000.00"), "0".repeat(64))).isEqualTo(expected);
    }

    @Test
    void paymentEventChainHash_printsMissingStatusesAsNone() {
        // V7 (Python): f"{12}:STATE_INIT:{None}:PENDING:{'0'*64}"
        assertThat(hmac("x", "ev-secret").paymentEventChainHash(12L, "STATE_INIT", null, "PENDING", "0".repeat(64)))
                .isEqualTo("f3a87ed3c9add05f4213d370926452495c35b597616b04ce3799efe1f677ef59");
    }

    @Test
    void orderCode_isMd5OfTheUidModTenToThe13() {
        // V8 (Python): int(md5(str(uuid)).hexdigest(), 16) % 10**13
        assertThat(PaymentService.generateOrderCode(UUID.fromString("3f2b1c9e-8d7a-4b6c-9e5f-1a2b3c4d5e6f")))
                .isEqualTo(6794783919851L);
    }

    @Test
    void quantize_isHalfUpToWholeVnd() {
        assertThat(WalletService.quantize(new BigDecimal("13500.50"))).isEqualByComparingTo("13501");
        assertThat(WalletService.quantize(new BigDecimal("13500.49"))).isEqualByComparingTo("13500");
        assertThat(WalletService.quantize(new BigDecimal("135000.00").multiply(PaymentService.PLATFORM_FEE_PERCENT)).toPlainString())
                .isEqualTo("13500");
    }

    /** Independently transcribed from PaymentService.__init__._allowed_transitions. */
    @Test
    void transitionTable_isDjangosVerbatim() {
        Map<PaymentStatus, Set<PaymentStatus>> expected = new EnumMap<>(PaymentStatus.class);
        expected.put(PENDING, EnumSet.of(HOLDING, SUCCESS, FAILED, CANCELLED));
        expected.put(HOLDING, EnumSet.of(RELEASED, REFUND_PENDING, REFUNDED, FAILED, CANCELLED));
        expected.put(SUCCESS, EnumSet.of(REFUND_PENDING, REFUNDED, CANCELLED));
        expected.put(REFUND_PENDING, EnumSet.of(REFUNDED, CANCELLED));
        expected.put(RELEASED, EnumSet.of(REFUND_PENDING, REFUNDED));
        expected.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        expected.put(CANCELLED, EnumSet.noneOf(PaymentStatus.class));
        expected.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        assertThat(PaymentStateService.ALLOWED_TRANSITIONS).isEqualTo(expected);
        // terminal states really are terminal: nothing leaves REFUNDED (no double refund via state)
        assertThat(PaymentStateService.ALLOWED_TRANSITIONS.get(REFUNDED)).isEmpty();
        // a replayed success webhook cannot pull RELEASED money back into escrow
        assertThat(PaymentStateService.ALLOWED_TRANSITIONS.get(RELEASED)).doesNotContain(HOLDING);
    }

    @Test
    void shouldRetryPayout_mirrorsDjango() {
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("status_code", 503), 1, 3)).isTrue();
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("status_code", 429), 2, 3)).isTrue();
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("status_code", 503), 3, 3)).isFalse(); // attempts exhausted
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("status_code", 400), 1, 3)).isFalse();
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("error", "gateway timeout"), 1, 3)).isTrue();
        // Django quirk kept: the SDK's own timeout message is "Request timed out", which does not
        // contain the token "timeout" — so it is NOT retried at this layer (the SDK already retried).
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("error", "Request timed out"), 1, 3)).isFalse();
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("error", "Connection reset"), 1, 3)).isTrue();
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("error", "Rate limit hit"), 1, 3)).isTrue();
        assertThat(WithdrawalService.shouldRetryPayout(Map.of("error", "Invalid account"), 1, 3)).isFalse();
    }

    @Test
    void maskAccountNumber_andThousands() {
        assertThat(WithdrawalService.maskAccountNumber("0123456789")).isEqualTo("******6789");
        assertThat(WithdrawalService.maskAccountNumber("1234")).isEqualTo("1234");
        assertThat(WithdrawalService.maskAccountNumber("")).isEmpty();
        assertThat(WithdrawalService.maskAccountNumber(null)).isEmpty();
        assertThat(WithdrawalService.thousands(new BigDecimal("19900000"))).isEqualTo("19,900,000");
        assertThat(WithdrawalService.thousands(new BigDecimal("10000"))).isEqualTo("10,000");
        assertThat(List.of(WithdrawalService.MIN_WITHDRAWAL, WithdrawalService.MAX_DAILY_WITHDRAWAL))
                .containsExactly(new BigDecimal("10000"), new BigDecimal("20000000"));
    }
}
