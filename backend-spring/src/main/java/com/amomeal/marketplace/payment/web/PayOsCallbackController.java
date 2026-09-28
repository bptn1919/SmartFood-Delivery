package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.service.PaymentService;
import com.amomeal.marketplace.payment.service.PaymentValueError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of ../../backend/payment/api.py::{@code payos_webhook} and {@code payos_return} — PLAIN
 * Django views mounted directly in {@code marketplace/urls.py} (not through ninja), so they
 * return raw {@code HttpResponse}/{@code JsonResponse} and are NOT wrapped in the
 * {@code {data, message_code, ...}} envelope. Both write straight to the servlet response and
 * return {@code void}, so {@code ResponseEnvelopeAdvice} never sees a body to wrap. Both
 * paths are {@code permitAll} in {@code SecurityConfig} (PayOS / the customer's browser call
 * them without a JWT), and every HTTP method reaches them, like the Django views.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class PayOsCallbackController {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    /**
     * Django {@code payos_webhook}: non-POST → 200; empty body (PayOS's "test webhook") → 200;
     * otherwise parse JSON and run {@code handle_payos_webhook}, swallowing EVERY error.
     * <b>Always HTTP 200 with an empty body</b> ("LUÔN TRẢ 200") — PayOS only needs the ack;
     * the signature check and all idempotency live in {@link PaymentService#handlePayosWebhook}.
     */
    @RequestMapping("/api/payment/payos/webhook")
    public void payosWebhook(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(200);
        if (!"POST".equals(request.getMethod())) {
            return;
        }
        try {
            byte[] body = request.getInputStream().readAllBytes();
            if (body.length == 0) {
                return;
            }
            Object data = objectMapper.readValue(body, Object.class);
            paymentService.handlePayosWebhook(data);
        } catch (IOException | RuntimeException ex) {
            // LOG nhưng KHÔNG FAIL
            log.warn("Webhook error: {}", ex.toString());
        }
    }

    /**
     * Django {@code payos_return} — where PayOS redirects the customer's browser. NOT a source
     * of truth, but it does call {@code sync_payment_by_order_code} when {@code code == "00"}
     * (which asks PayOS itself, so a forged query string cannot confirm an unpaid order).
     * Raw JSON: {@code {"success", "message", "order_code", "sync_result"}} etc.
     */
    @RequestMapping("/api/payment/payos/return")
    public void payosReturn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!"GET".equals(request.getMethod())) {
            body.put("success", false);
            body.put("message", "Invalid request method");
            writeJson(response, body);
            return;
        }
        // dict(request.GET.items()): the LAST value of a repeated parameter wins.
        String code = lastValue(request, "code");
        String orderCode = lastValue(request, "orderCode");

        if ("00".equals(code)) {
            Map<String, Object> syncResult = null;
            if (orderCode != null && !orderCode.isEmpty()) {
                try {
                    syncResult = paymentService.syncPaymentByOrderCode(PyCompat.toLong(orderCode));
                } catch (PaymentValueError | NumberFormatException | ArithmeticException ex) {
                    // Django: except (TypeError, ValueError) — which also swallows the ValueError an
                    // illegal state transition raises inside the sync.
                    syncResult = new LinkedHashMap<>();
                    syncResult.put("success", false);
                    syncResult.put("error", "Invalid order_code returned from PayOS");
                }
            }
            body.put("success", true);
            body.put("message", "Payment successful");
            body.put("order_code", orderCode);
            body.put("sync_result", syncResult);
        } else if ("01".equals(code)) {
            body.put("success", false);
            body.put("message", "Payment cancelled");
            body.put("order_code", orderCode);
        } else {
            body.put("success", false);
            body.put("message", "Payment failed");
            body.put("code", code);
            body.put("order_code", orderCode);
        }
        writeJson(response, body);
    }

    private static String lastValue(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null || values.length == 0 ? null : values[values.length - 1];
    }

    private void writeJson(HttpServletResponse response, Map<String, Object> body) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
    }
}
