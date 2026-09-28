package com.amomeal.marketplace.payment.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The PayOS HMAC-SHA256 signatures, ported byte-for-byte from the two places Django computes
 * them:
 * <ol>
 *   <li>{@link #createSignature(Map, String)} — {@code payment/providers/payos.py::
 *       PayOSPaymentProvider._create_signature}, used for (a) the create-payment-link request
 *       and (b) webhook verification. Hand-written in Django (not the SDK).</li>
 *   <li>{@link #sdkHeaderSignature(Map, String)} — the official {@code payos} SDK 1.1.0's
 *       {@code CryptoProvider.create_signature(key, data)} (encode_uri=True,
 *       sort_arrays=False), which Django's {@code create_payout} relies on via
 *       {@code payos_sdk.payouts.create(...)} for the {@code x-signature} request header and
 *       the response-signature check.</li>
 * </ol>
 * Field ORDER is part of the algorithm (keys are sorted by code point, exactly as Python's
 * {@code sorted()} does); do not "tidy" it.
 */
public final class PayOsSignature {

    private static final List<String> PAYMENT_REQUEST_FIELDS =
            List.of("amount", "cancelUrl", "description", "orderCode", "returnUrl");

    private PayOsSignature() {
    }

    /**
     * Django {@code _create_signature(data, checksum_key)}:
     * <ul>
     *   <li>if {@code data} has BOTH {@code orderCode} and {@code cancelUrl} keys → only the
     *       five payment-request fields are signed (PayOS docs' create-link rule);</li>
     *   <li>otherwise (webhook verification) → every key of {@code data};</li>
     *   <li>keys sorted; {@code None}, {@code "null"} and {@code "undefined"} become
     *       {@code ""}; every other value is Python {@code f"{value}"} — so a boolean prints
     *       {@code True}, a float {@code 1.5}, a nested dict its Python repr (NOT JSON; this
     *       differs from the official SDK, which JSON-encodes lists — preserved, see
     *       PROGRESS.md);</li>
     *   <li>joined {@code k=v&k=v}, HMAC-SHA256 with the checksum key, lowercase hex.</li>
     * </ul>
     */
    public static String createSignature(Map<String, Object> data, String checksumKey) {
        Map<String, Object> signatureData;
        if (data.containsKey("orderCode") && data.containsKey("cancelUrl")) {
            signatureData = new LinkedHashMap<>();
            for (String field : PAYMENT_REQUEST_FIELDS) {
                signatureData.put(field, data.get(field)); // data.get(...) -> None when absent
            }
        } else {
            signatureData = data;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : new TreeMap<>(signatureData).entrySet()) {
            Object value = e.getValue();
            String text = (value == null || "null".equals(value) || "undefined".equals(value))
                    ? ""
                    : PyCompat.str(value);
            parts.add(e.getKey() + "=" + text);
        }
        return hmacSha256Hex(checksumKey, String.join("&", parts));
    }

    /**
     * {@code payos} SDK {@code CryptoProvider.create_signature(secret_key, json_data)} with its
     * defaults ({@code encode_uri=True}, {@code sort_arrays=False}): deep-sort dict keys; lists
     * and dicts are compact JSON ({@code ensure_ascii=False}); {@code None} → {@code ""}; bools
     * {@code true}/{@code false}; everything else {@code str()}; then each key and value is
     * {@code urllib.parse.quote}d.
     */
    public static String sdkHeaderSignature(Map<String, Object> data, String checksumKey) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sorted = (Map<String, Object>) PyCompat.deepSort(data);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            Object value = e.getValue();
            String text;
            if (value instanceof List<?> || value instanceof Map<?, ?>) {
                text = PyCompat.jsonDumpsCompact(value);
            } else if (value == null) {
                text = "";
            } else if (value instanceof Boolean b) {
                text = b ? "true" : "false";
            } else {
                text = PyCompat.str(value);
            }
            parts.add(PyCompat.quote(e.getKey()) + "=" + PyCompat.quote(text));
        }
        return hmacSha256Hex(checksumKey, String.join("&", parts));
    }

    /**
     * {@code hmac.new(key.encode(), msg.encode(), hashlib.sha256).hexdigest()}.
     *
     * <p>An empty key is legal in Python and HMAC zero-pads keys to the block size, so
     * {@code b""} is equivalent to a single {@code 0x00} byte; Java's {@link SecretKeySpec}
     * rejects empty keys, hence the substitution. This matters: Django's
     * {@code WALLET_CHAIN_SECRET} defaults to {@code ""} when the env var is unset.
     */
    public static String hmacSha256Hex(String key, String message) {
        try {
            byte[] keyBytes = (key == null ? "" : key).getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length == 0) {
                keyBytes = new byte[]{0};
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    /** {@code hmac.compare_digest} — constant-time. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
