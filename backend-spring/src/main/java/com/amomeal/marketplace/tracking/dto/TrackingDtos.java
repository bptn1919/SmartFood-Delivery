package com.amomeal.marketplace.tracking.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/** Mirrors tracking/schemas/location_schemas.py (snake_case comes from the global Jackson strategy). */
public final class TrackingDtos {
    private TrackingDtos() {}

    public record UpdateLocationRequest(@NotNull Double latitude, @NotNull Double longitude, Double heading) {}

    public record SuccessResponse(boolean success, String message) {}

    public record NearbyChefItem(long chefId, String chefName, String avatar, double latitude, double longitude,
                                 double distanceKm, double avgRating, boolean isFoodSafetyCertified) {}

    public record NearbyChefsResponse(Map<String, Double> searchCenter, double radiusKm, List<NearbyChefItem> results) {}

    public record LocationPoint(Double latitude, Double longitude) {}

    public record OrderTrackingResponse(String orderId, String status, LocationPoint chefLocation,
                                        LocationPoint destination, String chefName) {}
}
