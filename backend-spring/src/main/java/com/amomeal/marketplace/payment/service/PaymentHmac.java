package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.payment.config.PaymentProperties;
import com.amomeal.marketplace.payment.provider.PayOsSignature;
import com.amomeal.marketplace.payment.provider.PyCompat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * The three internal HMAC-SHA256 constructions of ../../backend/payment/models.py, with their
 * message strings built exactly as Python's f-strings build them:
 * <ul>
 *   <li>{@code InternalWallet.compute_signature}: {@code f"{user_id}:{balance}:{pending_balance}"}</li>
 *   <li>{@code WalletTransaction.compute_chain_hash}:
 *       {@code f"{uid}:{user_id}:{tx_type}:{amount}:{balance_before}:{balance_after}:{previous_hash}"}</li>
 *   <li>{@code PaymentTransactionEvent.compute_chain_hash}:
 *       {@code f"{payment_id}:{event_type}:{status_from}:{status_to}:{previous_hash}"}
 *       — a missing status prints as {@code None}.</li>
 * </ul>
 * Wallet amounts go through {@link #canonicalAmount} (2 dp, plain) — DELIBERATE deviation from
 * Django, which used scale-preserving {@code str(Decimal)} and therefore could never verify a
 * wallet (payment open question #2, resolved "fix" by the user 2026-09-23). The payment-event
 * chain has no amounts and is unchanged (byte-identical to Django).
 */
@Component
@RequiredArgsConstructor
public class PaymentHmac {

    private final PaymentProperties properties;

    /**
     * The ONE string form every wallet amount is hashed/signed over: 2 decimal places
     * ({@code setScale(2)}), plain notation, no exponent — {@code 180000}, {@code 180000.0} and
     * {@code 180000.00} all become {@code "180000.00"}, which is also exactly what the
     * NUMERIC(15,2) columns read back as. Fix for payment open question #2 (Django embedded
     * {@code str(Decimal)}, whose scale differed between write time and DB read-back, so no
     * wallet with activity could ever verify).
     */
    public static String canonicalAmount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code HMAC(f"{user_id}:{balance}:{pending_balance}")} over {@link #canonicalAmount} strings. */
    public String walletSignature(Long userId, BigDecimal balance, BigDecimal pendingBalance) {
        return PayOsSignature.hmacSha256Hex(properties.getWalletChainSecret(),
                userId + ":" + canonicalAmount(balance) + ":" + canonicalAmount(pendingBalance));
    }

    /**
     * {@code HMAC(f"{uid}:{user_id}:{tx_type}:{amount}:{balance_before}:{balance_after}:{previous_hash}")}
     * over {@link #canonicalAmount} strings.
     */
    public String walletChainHash(UUID uid, Long userId, String txType, BigDecimal amount, BigDecimal balanceBefore,
                                  BigDecimal balanceAfter, String previousHash) {
        return PayOsSignature.hmacSha256Hex(properties.getWalletChainSecret(),
                uid + ":" + userId + ":" + txType + ":" + canonicalAmount(amount) + ":" + canonicalAmount(balanceBefore)
                        + ":" + canonicalAmount(balanceAfter) + ":" + previousHash);
    }

    public String paymentEventChainHash(Long paymentId, String eventType, String statusFrom, String statusTo,
                                        String previousHash) {
        return PayOsSignature.hmacSha256Hex(properties.getEventSecret(),
                paymentId + ":" + eventType + ":" + PyCompat.str(statusFrom) + ":" + PyCompat.str(statusTo)
                        + ":" + previousHash);
    }
}
