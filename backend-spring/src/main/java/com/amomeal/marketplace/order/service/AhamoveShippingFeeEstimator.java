package com.amomeal.marketplace.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 1:1 port of ../../backend/order/services/shipping_service.py::AhamoveAdapter.
 *
 * <p>Same staging base URL, same {@code {city}-BIKE} service id, same
 * JSON-encoded {@code path} query parameter with the two waypoints, the same
 * <b>5-second timeout</b> (Django's comment: "BẮT BUỘC có timeout=5.0 để chống
 * nghẽn Server") and the same {@code 15000} fallback on a non-200 response, a
 * missing {@code total_price} field, or any network failure.
 *
 * <p>{@code app.shipping.ahamove.base-url} has no Django equivalent — it exists
 * so tests can point this at an unreachable address and exercise the real
 * fallback path without reaching the internet, exactly like
 * {@code attachment}'s {@code s3-endpoint-override}.
 */
@Component
@Slf4j
public class AhamoveShippingFeeEstimator implements ShippingFeeEstimator {

    /** Django: the {@code 15000} returned on every failure branch. */
    public static final int FALLBACK_FEE = 15000;

    private static final String DEFAULT_CITY_CODE = "SGN";

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AhamoveShippingFeeEstimator(
            @Value("${app.shipping.ahamove.base-url:https://apistg.ahamove.com/v1}") String baseUrl,
            @Value("${app.shipping.ahamove.token:YOUR_AHAMOVE_STG_TOKEN}") String token,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public int estimateFee(Double pickupLat, Double pickupLng, Double dropoffLat, Double dropoffLng) {
        try {
            String path = objectMapper.writeValueAsString(new Object[]{
                    new Waypoint(pickupLat, pickupLng, "Điểm lấy món"),
                    new Waypoint(dropoffLat, dropoffLng, "Điểm giao món")
            });
            String url = baseUrl + "/order/estimated_fee"
                    + "?token=" + enc(token)
                    + "&order_time=0"
                    + "&service_id=" + enc(DEFAULT_CITY_CODE + "-BIKE")
                    + "&path=" + enc(path);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Ahamove API Error: [{}] {}", response.statusCode(), response.body());
                return FALLBACK_FEE;
            }
            JsonNode body = objectMapper.readTree(response.body());
            JsonNode totalPrice = body.get("total_price");
            return totalPrice == null || totalPrice.isNull() ? FALLBACK_FEE : totalPrice.asInt(FALLBACK_FEE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Ahamove Network Exception: {}", ex.toString());
            return FALLBACK_FEE;
        } catch (Exception ex) {
            // Django catches requests.exceptions.RequestException; anything else
            // (a malformed body, for instance) would propagate there. Widened here
            // deliberately: a shipping quote must never be able to 500 a checkout.
            log.warn("Ahamove Network Exception: {}", ex.toString());
            return FALLBACK_FEE;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Waypoint(Double lat, Double lng, String address) {
    }
}
