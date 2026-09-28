package com.amomeal.marketplace.payment.provider;

import com.amomeal.marketplace.payment.config.PayOsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of ../../backend/payment/providers/payos.py::PayOSPaymentProvider — the PayOS HTTP
 * integration, plain {@link HttpClient} + manual signing (no Java SDK: none is published by
 * PayOS for Java, and 1:1 fidelity with the Python code matters more than a wrapper).
 *
 * <p>Every method returns a Python-dict-shaped {@code Map} with the exact keys Django's
 * provider returns, because {@code PaymentService} persists these dicts verbatim into
 * {@code PaymentTransactionState.gateway_response} and reads them back by key.
 *
 * <h2>Endpoints (identical to Django)</h2>
 * <ul>
 *   <li>{@code POST {PAYOS_API_URL}/v2/payment-requests} — create link, body signed with
 *       {@link PayOsSignature#createSignature} (5 fields).</li>
 *   <li>{@code GET  {PAYOS_API_URL}/v2/payment-requests/{orderCode}} — payment info.</li>
 *   <li>{@code POST {PAYOS_API_URL}/v2/payment-requests/{orderCode}/cancel}.</li>
 *   <li>{@code GET  {PAYOS_API_URL}/v2/payment-requests/{orderCode}/invoices}.</li>
 *   <li>{@code POST {PAYOS_BASE_URL}/v1/payouts} — what the official {@code payos} SDK 1.1.0
 *       does inside {@code payos_sdk.payouts.create(...)}: {@code x-idempotency-key} +
 *       {@code x-signature} headers, response data verified against the response's
 *       {@code x-signature} header, up to 2 retries on 408/429/5xx/timeout/connect error.</li>
 * </ul>
 * Not ported (called from nowhere in Django): {@code download_invoice},
 * {@code confirm_webhook_url}, {@code get_payout_info}, {@code _get_status_message}.
 */
@Component
@Slf4j
public class PayOsProvider {

    private final PayOsProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PayOsProvider(PayOsProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // =====================================================================
    // create_payment
    // =====================================================================

    /**
     * Django {@code create_payment(order_code, amount, description, buyer_*=None, ...)}. Only
     * the three arguments {@code PaymentService.create_payment} actually passes are exposed
     * (it passes {@code buyer_name/email/phone=None}, which Django then omits from the body).
     *
     * @throws IllegalStateException when PayOS answers {@code code == "00"} without one of the
     *         fields Django reads with {@code result["data"][...]} (Python KeyError — NOT
     *         caught by the provider's {@code except RequestException}, so it propagates)
     */
    public Map<String, Object> createPayment(long orderCode, long amount, String description) {
        props.requirePaymentCredentials();
        Map<String, Object> paymentData = new LinkedHashMap<>();
        paymentData.put("orderCode", orderCode);
        paymentData.put("amount", amount);
        paymentData.put("description", description);
        paymentData.put("cancelUrl", props.getCancelUrl());
        paymentData.put("returnUrl", props.getReturnUrl());
        paymentData.put("signature", PayOsSignature.createSignature(paymentData, props.getChecksumKey()));

        Map<String, Object> result;
        try {
            result = sendJson("POST", props.getApiUrl() + "/v2/payment-requests",
                    objectMapper.writeValueAsString(paymentData), true);
        } catch (RequestFailure ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("error", ex.getMessage());
            failure.put("message", "Failed to create payment");
            return failure;
        }

        if ("00".equals(result.get("code"))) {
            Map<String, Object> data = requireMap(result, "data");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("checkout_url", require(data, "checkoutUrl"));
            out.put("qr_code", require(data, "qrCode"));
            out.put("payment_link_id", require(data, "paymentLinkId"));
            out.put("order_code", require(data, "orderCode"));
            out.put("amount", require(data, "amount"));
            out.put("account_number", data.get("accountNumber"));
            out.put("account_name", data.get("accountName"));
            out.put("bin", data.get("bin"));
            out.put("currency", data.getOrDefault("currency", "VND"));
            out.put("status", require(data, "status"));
            out.put("description", data.get("description"));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", result.getOrDefault("desc", "Unknown error"));
        out.put("code", result.get("code"));
        return out;
    }

    // =====================================================================
    // get_payment_info / cancel_payment / get_payment_invoices
    // =====================================================================

    /** Django {@code get_payment_info(order_code)}. */
    public Map<String, Object> getPaymentInfo(long orderCode) {
        props.requirePaymentCredentials();
        Map<String, Object> result;
        try {
            result = sendJson("GET", props.getApiUrl() + "/v2/payment-requests/" + orderCode, null, false);
        } catch (RequestFailure ex) {
            return failure(ex.getMessage());
        }
        if ("00".equals(result.get("code"))) {
            Map<String, Object> data = requireMap(result, "data");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("payment_link_id", data.get("id"));
            out.put("order_code", require(data, "orderCode"));
            out.put("amount", require(data, "amount"));
            out.put("amount_paid", data.getOrDefault("amountPaid", 0));
            out.put("amount_remaining", data.getOrDefault("amountRemaining", 0));
            out.put("status", require(data, "status"));
            out.put("created_at", data.get("createdAt"));
            out.put("transactions", data.getOrDefault("transactions", List.of()));
            return out;
        }
        return failure(result.getOrDefault("desc", "Unknown error"));
    }

    /** Django {@code cancel_payment(order_code, reason)}. */
    public Map<String, Object> cancelPayment(long orderCode, String reason) {
        props.requirePaymentCredentials();
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null && !reason.isEmpty()) {
            body.put("cancellationReason", reason);
        }
        Map<String, Object> result;
        try {
            result = sendJson("POST", props.getApiUrl() + "/v2/payment-requests/" + orderCode + "/cancel",
                    objectMapper.writeValueAsString(body), true);
        } catch (RequestFailure ex) {
            return failure(ex.getMessage());
        }
        if ("00".equals(result.get("code"))) {
            Map<String, Object> data = requireMap(result, "data");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("status", require(data, "status"));
            out.put("cancelled_at", data.get("canceledAt"));
            return out;
        }
        return failure(result.getOrDefault("desc", "Unknown error"));
    }

    /** Django {@code get_payment_invoices(order_code)}. */
    public Map<String, Object> getPaymentInvoices(long orderCode) {
        props.requirePaymentCredentials();
        Map<String, Object> result;
        try {
            result = sendJson("GET", props.getApiUrl() + "/v2/payment-requests/" + orderCode + "/invoices", null, false);
        } catch (RequestFailure ex) {
            return failure(ex.getMessage());
        }
        if ("00".equals(result.get("code"))) {
            Map<String, Object> data = requireMap(result, "data");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("invoices", data.getOrDefault("invoices", List.of()));
            return out;
        }
        return failure(result.getOrDefault("desc", "Unknown error"));
    }

    // =====================================================================
    // verify_webhook_data
    // =====================================================================

    /**
     * Django {@code verify_webhook_data(webhook_data)}. Recomputes the signature over EVERY key
     * of {@code webhook_data["data"]} and compares it with {@code webhook_data["signature"]}.
     *
     * <p>PORT-NOTE (preserved, flagged): Django compares with
     * {@code calculated.lower() == received.lower()} — a plain, non-constant-time string
     * comparison (unlike {@code InternalWallet.verify_signature}, which uses
     * {@code hmac.compare_digest}). Ported as the same plain case-insensitive equality.
     *
     * <p>Any unexpected shape (non-dict payload, non-string signature, non-dict data) lands
     * in Django's {@code except Exception} and yields {@code is_valid=False}, code "99".
     */
    public Map<String, Object> verifyWebhookData(Object webhookData) {
        try {
            if (!(webhookData instanceof Map<?, ?> rawWebhook)) {
                throw new IllegalArgumentException("'" + pyTypeName(webhookData) + "' object has no attribute 'get'");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> webhook = (Map<String, Object>) rawWebhook;
            Object receivedSignature = webhook.getOrDefault("signature", "");
            Object data = webhook.getOrDefault("data", Map.of());

            if (!PyCompat.truthy(data)) {
                log.error("Missing 'data' field in webhook: {}", webhook);
                return invalid("Missing data field");
            }
            if (!(data instanceof Map<?, ?> rawData)) {
                throw new IllegalArgumentException("'" + pyTypeName(data) + "' object has no attribute 'keys'");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) rawData;

            String calculated = PayOsSignature.createSignature(dataMap, props.getChecksumKey());
            if (!(receivedSignature instanceof String received)) {
                throw new IllegalArgumentException("'" + pyTypeName(receivedSignature) + "' object has no attribute 'lower'");
            }
            boolean isValid = calculated.toLowerCase().equals(received.toLowerCase());
            boolean isSuccess = "00".equals(webhook.get("code"))
                    && PyCompat.equalsTrue(webhook.get("success"))
                    && "00".equals(dataMap.get("code"));

            log.info("Signature valid: {}, Payment success: {}", isValid, isSuccess);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("is_valid", isValid);
            out.put("is_success", isSuccess);
            out.put("code", webhook.getOrDefault("code", ""));
            out.put("desc", webhook.getOrDefault("desc", ""));
            out.put("order_code", dataMap.get("orderCode"));
            out.put("amount", dataMap.get("amount"));
            out.put("description", dataMap.get("description"));
            out.put("account_number", dataMap.get("accountNumber"));
            out.put("reference", dataMap.get("reference"));
            out.put("transaction_datetime", dataMap.get("transactionDateTime"));
            out.put("payment_link_id", dataMap.get("paymentLinkId"));
            out.put("currency", dataMap.getOrDefault("currency", "VND"));
            return out;
        } catch (RuntimeException ex) {
            log.error("Error in verify_webhook_data: {}", ex.toString());
            return invalid("Verification error: " + ex.getMessage());
        }
    }

    private static Map<String, Object> invalid(String desc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("is_valid", false);
        out.put("is_success", false);
        out.put("code", "99");
        out.put("desc", desc);
        out.put("order_code", null);
        out.put("amount", null);
        out.put("payment_link_id", null);
        return out;
    }

    // =====================================================================
    // create_payout (payos SDK payouts.create)
    // =====================================================================

    /**
     * Django {@code create_payout(reference_id, amount, description, to_bin, to_account_number,
     * idempotency_key, category)} — i.e. the {@code payos} SDK's {@code payouts.create(...)}.
     * Never throws: every failure becomes {@code {"success": False, "error", "status_code",
     * "message": "Failed to create payout"}} like Django's {@code except Exception}.
     */
    public Map<String, Object> createPayout(String referenceId, long amount, String description, String toBin,
                                            String toAccountNumber, String idempotencyKey, List<String> category) {
        try {
            if (description != null && description.length() > 25) {
                description = description.substring(0, 25); // "PayOS limit (max 25 characters)"
            }
            // pydantic PayoutRequest: every field but category is a required str/int.
            if (referenceId == null || description == null || toBin == null || toAccountNumber == null) {
                throw new SdkError("1 validation error for PayoutRequest: field required", null);
            }
            String clientId = props.effectivePayoutClientId();
            String apiKey = props.effectivePayoutApiKey();
            String checksumKey = props.effectivePayoutChecksumKey();
            if (isBlank(clientId) || isBlank(apiKey) || isBlank(checksumKey)) {
                throw new SdkError("Missing PayOS payout configuration. Check environment variables.", null);
            }

            // PayoutRequest.model_dump(by_alias=True): pydantic field order, camelCase aliases.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("referenceId", referenceId);
            body.put("amount", amount);
            body.put("description", description);
            body.put("toBin", toBin);
            body.put("toAccountNumber", toAccountNumber);
            body.put("category", category);
            String signature = PayOsSignature.sdkHeaderSignature(body, checksumKey);
            String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();

            Map<String, Object> data = sdkPost(props.getSdkBaseUrl() + "/v1/payouts", objectMapper.writeValueAsString(body),
                    clientId, apiKey, checksumKey, key, signature);

            Object id = data.get("id");
            Object respReferenceId = data.get("referenceId");
            Object approvalState = data.get("approvalState");
            Object createdAt = data.get("createdAt");
            Object rawTransactions = data.get("transactions");
            if (id == null || respReferenceId == null || approvalState == null || createdAt == null
                    || !(rawTransactions instanceof List<?>)) {
                throw new SdkError("validation error for Payout", null);
            }
            List<Map<String, Object>> transactions = new ArrayList<>();
            for (Object tx : (List<?>) rawTransactions) {
                if (tx instanceof Map<?, ?> m) {
                    transactions.add(snakeCaseTransaction(m));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("payout_id", id);
            out.put("reference_id", respReferenceId);
            out.put("transactions", transactions);
            out.put("approval_state", approvalState);
            out.put("created_at", createdAt);
            out.put("message", "Payout created successfully");
            return out;
        } catch (SdkError ex) {
            return payoutFailure(ex.getMessage(), ex.statusCode);
        } catch (RuntimeException ex) {
            return payoutFailure(String.valueOf(ex.getMessage()), null);
        }
    }

    /** Django serializes each SDK PayoutTransaction via {@code vars(tx)} — pydantic's snake_case field names. */
    private static Map<String, Object> snakeCaseTransaction(Map<?, ?> tx) {
        String[][] fields = {
                {"id", "id"}, {"reference_id", "referenceId"}, {"amount", "amount"}, {"description", "description"},
                {"to_bin", "toBin"}, {"to_account_number", "toAccountNumber"}, {"to_account_name", "toAccountName"},
                {"reference", "reference"}, {"transaction_datetime", "transactionDatetime"},
                {"error_message", "errorMessage"}, {"error_code", "errorCode"}, {"state", "state"}};
        Map<String, Object> out = new LinkedHashMap<>();
        for (String[] f : fields) {
            out.put(f[0], tx.get(f[1]));
        }
        return out;
    }

    private static Map<String, Object> payoutFailure(String error, Integer statusCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", error);
        out.put("status_code", statusCode);
        out.put("message", "Failed to create payout");
        return out;
    }

    /** The SDK's {@code request()} loop for a header-signed POST with header-signed response. */
    private Map<String, Object> sdkPost(String url, String body, String clientId, String apiKey, String checksumKey,
                                        String idempotencyKey, String signature) {
        int maxRetries = props.getSdkMaxRetries();
        for (int retry = 0; ; retry++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getSdkTimeoutSeconds()))
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PayOS/python/1.1.0 (AmoMeal Spring port)")
                    .header("x-idempotency-key", idempotencyKey)
                    .header("x-signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException ex) {
                // httpx.ConnectTimeout is a TimeoutException too -> same SDK branch.
                if (retry < maxRetries) {
                    sleepRetry(retry);
                    continue;
                }
                throw new SdkError("Request timed out", null);
            } catch (ConnectException ex) {
                if (retry < maxRetries) {
                    sleepRetry(retry);
                    continue;
                }
                throw new SdkError("Failed to connect", null);
            } catch (IOException ex) {
                throw new SdkError(String.valueOf(ex.getMessage()), null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SdkError("interrupted", null);
            }

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if ((status == 408 || status == 429 || status >= 500) && retry < maxRetries) {
                    sleepRetry(retry);
                    continue;
                }
                String desc = null;
                try {
                    Object parsed = objectMapper.readValue(response.body(), Object.class);
                    if (parsed instanceof Map<?, ?> m && m.get("desc") instanceof String d) {
                        desc = d;
                    }
                } catch (RuntimeException ignored) {
                    // SDK: error_data stays None when the body is not JSON
                }
                throw new SdkError(desc != null && !desc.isEmpty() ? desc : "HTTP " + status + " error", status);
            }
            Map<String, Object> json;
            try {
                Object parsed = objectMapper.readValue(response.body(), Object.class);
                if (!(parsed instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("not an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) parsed;
                json = cast;
            } catch (RuntimeException ex) {
                String text = response.body();
                throw new SdkError("Invalid JSON response: " + text.substring(0, Math.min(200, text.length())), status);
            }
            Object code = json.get("code");
            Object desc = json.get("desc");
            Object data = json.get("data");
            if (!"00".equals(code)) {
                String message = desc instanceof String d && !d.isEmpty() ? d : "API error";
                throw new SdkError(message, status);
            }
            // signature_response="header": verify data against the response's x-signature.
            if (PyCompat.truthy(data)) {
                String responseSignature = response.headers().firstValue("x-signature").orElse(null);
                if (responseSignature == null || responseSignature.isEmpty()) {
                    throw new SdkError("Response signature missing", null);
                }
                if (!(data instanceof Map<?, ?>)) {
                    throw new SdkError("Response signature verification failed", null);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) data;
                String expected = PayOsSignature.sdkHeaderSignature(dataMap, checksumKey);
                if (!responseSignature.equals(expected)) {
                    throw new SdkError("Response signature verification failed", null);
                }
                return dataMap;
            }
            throw new SdkError("validation error for Payout", null);
        }
    }

    private void sleepRetry(int retryCount) {
        double base = props.getSdkRetryBaseDelayMs();
        double delay = Math.min(base * Math.pow(2, retryCount), 10_000) * (0.75 + ThreadLocalRandom.current().nextDouble() * 0.25);
        try {
            Thread.sleep((long) delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Python {@code payos.PayOSError}/{@code APIError}: message + optional {@code status_code}. */
    private static final class SdkError extends RuntimeException {
        private final Integer statusCode;

        SdkError(String message, Integer statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    // =====================================================================
    // requests.* emulation
    // =====================================================================

    /** Anything Django's {@code except requests.exceptions.RequestException} catches. */
    private static final class RequestFailure extends RuntimeException {
        RequestFailure(String message) {
            super(message);
        }
    }

    /**
     * {@code requests.get/post(..., timeout=30)} + {@code raise_for_status()} + {@code .json()},
     * with the {@code x-client-id}/{@code x-api-key} headers Django sends.
     */
    private Map<String, Object> sendJson(String method, String url, String jsonBody, boolean contentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("x-client-id", props.getClientId())
                .header("x-api-key", props.getApiKey());
        if (contentType) {
            builder.header("Content-Type", "application/json");
        }
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            builder.GET();
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new RequestFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RequestFailure("interrupted");
        }
        int status = response.statusCode();
        if (status >= 400) {
            String kind = status < 500 ? "Client" : "Server";
            throw new RequestFailure(status + " " + kind + " Error: " + reason(status) + " for url: " + url);
        }
        try {
            Object parsed = objectMapper.readValue(response.body(), Object.class);
            if (parsed instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
            // A JSON array/scalar: Django's result.get(...) would AttributeError — surfaces as a failure dict here.
            throw new RequestFailure("Unexpected JSON response: " + response.body());
        } catch (RequestFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RequestFailure("Expecting value: " + ex.getMessage()); // requests.JSONDecodeError
        }
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    private static Map<String, Object> failure(Object error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", error);
        return out;
    }

    /** Python {@code result["data"][key]} — KeyError propagates (not a RequestException). */
    private static Object require(Map<String, Object> data, String key) {
        if (!data.containsKey(key)) {
            throw new IllegalStateException("KeyError: '" + key + "'");
        }
        return data.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Map<String, Object> result, String key) {
        Object value = require(result, key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("TypeError: '" + key + "' is not a dict");
        }
        return (Map<String, Object>) value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private static String pyTypeName(Object value) {
        if (value == null) {
            return "NoneType";
        }
        if (value instanceof String) {
            return "str";
        }
        if (value instanceof List<?>) {
            return "list";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "int";
        }
        if (value instanceof Double) {
            return "float";
        }
        return value.getClass().getSimpleName();
    }
}
