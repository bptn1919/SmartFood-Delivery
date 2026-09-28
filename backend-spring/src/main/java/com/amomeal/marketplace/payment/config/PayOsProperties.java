package com.amomeal.marketplace.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayOS configuration — every field maps 1:1 to the environment variable Django's
 * {@code payment/providers/payos.py::PayOSPaymentProvider.__init__} reads (see
 * application.yml for the {@code ${PAYOS_*}} bindings).
 *
 * <p>PORT-NOTE: Django raises {@code ValueError("Missing PayOS configuration...")} in the
 * provider CONSTRUCTOR, and {@code PaymentService()} is instantiated by {@code OrderService()}
 * — so in Django a missing PAYOS_* env var breaks every order and payment endpoint, not just
 * PayOS calls. This port checks lazily ({@link #requirePaymentCredentials()}) at the moment a
 * PayOS call is made, so the application (and COD ordering) still work without PayOS
 * credentials. Same error text.
 */
@ConfigurationProperties(prefix = "app.payos")
@Getter
@Setter
public class PayOsProperties {

    /** PAYOS_CLIENT_ID */
    private String clientId;
    /** PAYOS_API_KEY */
    private String apiKey;
    /** PAYOS_CHECKSUM_KEY */
    private String checksumKey;

    /** PAYOS_PAYOUT_CLIENT_ID (falls back to clientId, like Django's {@code or}). */
    private String payoutClientId;
    /** PAYOS_PAYOUT_API_KEY (falls back to apiKey). */
    private String payoutApiKey;
    /** PAYOS_PAYOUT_CHECKSUM_KEY (falls back to checksumKey). */
    private String payoutChecksumKey;

    /** PAYOS_API_URL — base of the hand-written {@code requests} calls (payment links). */
    private String apiUrl = "https://api-merchant.payos.vn";

    /**
     * PAYOS_BASE_URL — base the official {@code payos} SDK uses for payouts. A DIFFERENT env
     * var from PAYOS_API_URL in Django (the SDK reads its own); both default to the same host.
     */
    private String sdkBaseUrl = "https://api-merchant.payos.vn";

    /** PAYOS_RETURN_URL */
    private String returnUrl;
    /** PAYOS_CANCEL_URL */
    private String cancelUrl;

    /** Django {@code requests.*(..., timeout=30)}. */
    private int timeoutSeconds = 30;

    /** payos SDK {@code DEFAULT_TIMEOUT = 60.0}. */
    private int sdkTimeoutSeconds = 60;

    /** payos SDK {@code DEFAULT_MAX_RETRIES = 2} (on 408/429/5xx/timeouts/connect errors). */
    private int sdkMaxRetries = 2;

    /** payos SDK exponential backoff base (0.5 s * 2^n * jitter[0.75,1], capped at 10 s). Tests shrink it. */
    private long sdkRetryBaseDelayMs = 500;

    public String effectivePayoutClientId() {
        return blankToNull(payoutClientId) != null ? payoutClientId : clientId;
    }

    public String effectivePayoutApiKey() {
        return blankToNull(payoutApiKey) != null ? payoutApiKey : apiKey;
    }

    public String effectivePayoutChecksumKey() {
        return blankToNull(payoutChecksumKey) != null ? payoutChecksumKey : checksumKey;
    }

    /** Django {@code _resolve_credentials()} / the constructor's {@code all([...])} check. */
    public void requirePaymentCredentials() {
        if (blankToNull(clientId) == null || blankToNull(apiKey) == null || blankToNull(checksumKey) == null) {
            throw new IllegalStateException("Missing PayOS configuration. Check environment variables.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
