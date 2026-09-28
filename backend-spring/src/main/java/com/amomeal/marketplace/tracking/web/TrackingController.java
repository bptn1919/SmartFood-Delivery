package com.amomeal.marketplace.tracking.web;

import com.amomeal.marketplace.tracking.dto.TrackingDtos.*;
import com.amomeal.marketplace.tracking.exception.TrackingValidationException;
import com.amomeal.marketplace.tracking.service.LocationService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Mirrors tracking/api.py::TrackingController (prefix "tracking"; role gates = require_group). */
@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {

    private final LocationService service;

    @PostMapping("/chef/location")
    @PreAuthorize("hasRole('CHEF')")
    public SuccessResponse updateLocation(@AuthenticationPrincipal CustomUser user,
                                          @Valid @RequestBody UpdateLocationRequest body) {
        service.updateChefLocation(user, body.latitude(), body.longitude(), body.heading());
        return new SuccessResponse(true, "Location updated successfully");
    }

    @GetMapping("/chefs/nearby")
    @PreAuthorize("hasRole('CUSTOMER')")
    public NearbyChefsResponse nearbyChefs(@RequestParam(required = false) Double lat,
                                           @RequestParam(required = false) Double lng,
                                           @RequestParam(name = "radius_km", defaultValue = "5.0") double radiusKm) {
        if (lat == null || lng == null) {
            throw new TrackingValidationException("lat and lng are required");
        }
        return service.getNearbyChefs(lat, lng, radiusKm);
    }

    @GetMapping("/order/{orderId}/tracking")
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderTrackingResponse orderTracking(@AuthenticationPrincipal CustomUser user, @PathVariable UUID orderId) {
        return service.getOrderTracking(user, orderId);
    }
}
