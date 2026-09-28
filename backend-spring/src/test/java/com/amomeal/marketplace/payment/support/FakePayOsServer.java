package com.amomeal.marketplace.payment.support;

import com.amomeal.marketplace.payment.provider.PayOsSignature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server (JDK {@link HttpServer}) that speaks the PayOS merchant API as the Django
 * provider/SDK use it — so the payment module's actual {@code HttpClient} code, request signing
 * and response-signature verification are exercised end to end without ever calling PayOS.
 *
 * <p>One process-wide instance ({@link #get()}), started lazily and registered by
 * {@code TestcontainersConfiguration} (so every full-stack test, including {@code order}'s
 * existing PayOS place-order tests, points {@code app.payos.*} here). Tests steer it per
 * order code / globally and inspect the recorded requests.
 */
public final class FakePayOsServer {

    public static final String CLIENT_ID = "test-client-id";
    public static final String API_KEY = "test-api-key";
    public static final String CHECKSUM_KEY = "test-checksum-key";

    private static FakePayOsServer instance;

    private final HttpServer server;
    private final ObjectMapper json = JsonMapper.builder().build();

    /** Recorded requests: method, path, headers (lower-cased names), body. */
    public record Recorded(String method, String path, Map<String, String> headers, String body) {
    }

    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    /** orderCode -> PayOS link status returned by GET /v2/payment-requests/{code}. */
    private final Map<Long, String> linkStatus = new ConcurrentHashMap<>();
    /** order codes whose GET lookup answers HTTP 500. */
    private final java.util.Set<Long> failLookup = ConcurrentHashMap.newKeySet();
    private volatile boolean failCreate = false;
    private volatile boolean failCancel = false;

    /** Payout behavior: "ok", "http500", "http400", "badsig", "nosig", "code01". */
    private volatile String payoutMode = "ok";
    private final AtomicInteger payoutCalls = new AtomicInteger();

    public static synchronized FakePayOsServer get() {
        if (instance == null) {
            instance = new FakePayOsServer();
        }
        return instance;
    }

    private FakePayOsServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ steering

    public void reset() {
        requests.clear();
        linkStatus.clear();
        failLookup.clear();
        failCreate = false;
        failCancel = false;
        payoutMode = "ok";
        payoutCalls.set(0);
    }

    public void setLinkStatus(long orderCode, String status) {
        linkStatus.put(orderCode, status);
    }

    public void setFailLookup(long orderCode, boolean fail) {
        if (fail) {
            failLookup.add(orderCode);
        } else {
            failLookup.remove(orderCode);
        }
    }

    public void setFailCreate(boolean fail) {
        failCreate = fail;
    }

    public void setFailCancel(boolean fail) {
        failCancel = fail;
    }

    public void setPayoutMode(String mode) {
        payoutMode = mode;
    }

    public int payoutCalls() {
        return payoutCalls.get();
    }

    public List<Recorded> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    public List<Recorded> requestsTo(String pathPrefix) {
        return requests().stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    // ------------------------------------------------------------------ PayOS emulation

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? null : v.get(0)));
        requests.add(new Recorded(method, path, headers, body));

        if (!CLIENT_ID.equals(headers.get("x-client-id")) || !API_KEY.equals(headers.get("x-api-key"))) {
            respond(ex, 401, Map.of("code", "401", "desc", "Unauthorized"), null);
            return;
        }
        String[] seg = path.split("/");
        if ("POST".equals(method) && path.equals("/v2/payment-requests")) {
            createLink(ex, body);
        } else if ("GET".equals(method) && seg.length == 4 && path.startsWith("/v2/payment-requests/")) {
            long code = Long.parseLong(seg[3]);
            if (failLookup.contains(code)) {
                respond(ex, 500, Map.of("code", "500", "desc", "Internal error"), null);
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", "link-" + code);
            data.put("orderCode", code);
            data.put("amount", 100000);
            data.put("amountPaid", "PAID".equals(linkStatus.get(code)) ? 100000 : 0);
            data.put("amountRemaining", 0);
            data.put("status", linkStatus.getOrDefault(code, "PENDING"));
            data.put("createdAt", "2026-09-23T10:00:00+07:00");
            data.put("transactions", List.of());
            respond(ex, 200, envelope(data), null);
        } else if ("POST".equals(method) && seg.length == 5 && "cancel".equals(seg[4])) {
            if (failCancel) {
                respond(ex, 200, Map.of("code", "101", "desc", "Cannot cancel"), null);
                return;
            }
            long code = Long.parseLong(seg[3]);
            linkStatus.put(code, "CANCELLED");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "CANCELLED");
            data.put("canceledAt", "2026-09-23T10:05:00+07:00");
            respond(ex, 200, envelope(data), null);
        } else if ("GET".equals(method) && seg.length == 5 && "invoices".equals(seg[4])) {
            Map<String, Object> invoice = new LinkedHashMap<>();
            invoice.put("invoiceId", "inv-1");
            invoice.put("invoiceNumber", "0000001");
            invoice.put("issuedTimestamp", 1700000000);
            invoice.put("issuedDatetime", "2023-11-14T22:13:20+07:00");
            invoice.put("transactionId", "tx-1");
            invoice.put("reservationCode", "RC1");
            invoice.put("codeOfTax", "CT1");
            respond(ex, 200, envelope(Map.of("invoices", List.of(invoice))), null);
        } else if ("POST".equals(method) && path.equals("/v1/payouts")) {
            payout(ex, body, headers);
        } else {
            respond(ex, 404, Map.of("code", "404", "desc", "Not found"), null);
        }
    }

    @SuppressWarnings("unchecked")
    private void createLink(HttpExchange ex, String body) throws IOException {
        Map<String, Object> req = json.readValue(body, Map.class);
        String expected = PayOsSignature.createSignature(req, CHECKSUM_KEY);
        if (failCreate || !expected.equals(req.get("signature"))) {
            respond(ex, 200, Map.of("code", "20", "desc", failCreate ? "Forced failure" : "Invalid signature"), null);
            return;
        }
        Object orderCode = req.get("orderCode");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bin", "970422");
        data.put("accountNumber", "113366668888");
        data.put("accountName", "AMOMEAL TEST");
        data.put("amount", req.get("amount"));
        data.put("description", req.get("description"));
        data.put("orderCode", orderCode);
        data.put("currency", "VND");
        data.put("paymentLinkId", "link-" + orderCode);
        data.put("status", "PENDING");
        data.put("checkoutUrl", "https://pay.payos.test/web/link-" + orderCode);
        data.put("qrCode", "00020101021238570010A000000727QR-" + orderCode);
        linkStatus.putIfAbsent(((Number) orderCode).longValue(), "PENDING");
        respond(ex, 200, envelope(data), null);
    }

    @SuppressWarnings("unchecked")
    private void payout(HttpExchange ex, String body, Map<String, String> headers) throws IOException {
        payoutCalls.incrementAndGet();
        Map<String, Object> req = json.readValue(body, Map.class);
        String expected = PayOsSignature.sdkHeaderSignature(req, CHECKSUM_KEY);
        if (!expected.equals(headers.get("x-signature"))) {
            respond(ex, 400, Map.of("code", "14", "desc", "Invalid request signature"), null);
            return;
        }
        switch (payoutMode) {
            case "http500" -> respond(ex, 500, Map.of("code", "20", "desc", "Internal error"), null);
            case "http400" -> respond(ex, 400, Map.of("code", "20", "desc", "Tai khoan khong hop le"), null);
            case "code01" -> respond(ex, 200, Map.of("code", "01", "desc", "Payout rejected"), null);
            default -> {
                Map<String, Object> tx = new LinkedHashMap<>();
                tx.put("id", "ptx-1");
                tx.put("referenceId", req.get("referenceId"));
                tx.put("amount", req.get("amount"));
                tx.put("description", req.get("description"));
                tx.put("toBin", req.get("toBin"));
                tx.put("toAccountNumber", req.get("toAccountNumber"));
                tx.put("toAccountName", "NGUYEN VAN A");
                tx.put("reference", null);
                tx.put("transactionDatetime", null);
                tx.put("errorMessage", null);
                tx.put("errorCode", null);
                tx.put("state", "RECEIVED");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", "po-" + req.get("referenceId"));
                data.put("referenceId", req.get("referenceId"));
                data.put("transactions", List.of(tx));
                data.put("category", req.get("category"));
                data.put("approvalState", "APPROVED");
                data.put("createdAt", "2026-09-23T10:00:00+07:00");
                String signature = PayOsSignature.sdkHeaderSignature(data, CHECKSUM_KEY);
                if ("badsig".equals(payoutMode)) {
                    signature = "0".repeat(64);
                }
                respond(ex, 200, envelope(data), "nosig".equals(payoutMode) ? null : signature);
            }
        }
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", "00");
        out.put("desc", "success");
        out.put("data", data);
        out.put("signature", "unused-in-these-paths");
        return out;
    }

    private void respond(HttpExchange ex, int status, Object body, String signatureHeader) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (signatureHeader != null) {
            ex.getResponseHeaders().add("x-signature", signatureHeader);
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
