package com.amomeal.marketplace.tracking.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.profile.service.ChefCertificationProvider;
import com.amomeal.marketplace.tracking.dto.TrackingDtos.*;
import com.amomeal.marketplace.tracking.entity.ChefLocation;
import com.amomeal.marketplace.tracking.exception.TrackingNotFoundException;
import com.amomeal.marketplace.tracking.repository.ChefLocationRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of ../../backend/tracking/services/location_service.py + the three endpoint bodies in tracking/api.py.
 * Broadcast on update = Django's {@code group_send}, done after the DB commit here.
 */
@Service
public class LocationService {

    private final ChefLocationRepository locationRepository;
    private final ChefProfileRepository chefProfileRepository;
    private final ChefCertificationProvider certificationProvider;
    private final OrderRepository orderRepository;
    private final LocationBroadcaster broadcaster;
    private final boolean preserveZeroAvgRatingBug;
    private final boolean preserveOrderTrackingBug;

    public LocationService(ChefLocationRepository locationRepository, ChefProfileRepository chefProfileRepository,
                           ChefCertificationProvider certificationProvider, OrderRepository orderRepository,
                           LocationBroadcaster broadcaster,
                           @Value("${app.tracking.preserve-zero-avg-rating-bug:true}") boolean preserveZeroAvgRatingBug,
                           @Value("${app.tracking.preserve-order-tracking-bug:false}") boolean preserveOrderTrackingBug) {
        this.locationRepository = locationRepository;
        this.chefProfileRepository = chefProfileRepository;
        this.certificationProvider = certificationProvider;
        this.orderRepository = orderRepository;
        this.broadcaster = broadcaster;
        this.preserveZeroAvgRatingBug = preserveZeroAvgRatingBug;
        this.preserveOrderTrackingBug = preserveOrderTrackingBug;
    }

    /** Django update_chef_location: get_or_create, overwrite lat/lng, heading only when given, save, broadcast. */
    @Transactional
    public ChefLocation updateChefLocation(CustomUser user, double latitude, double longitude, Double heading) {
        ChefLocation loc = locationRepository.findByChefId(user.getId())
                .orElseGet(() -> ChefLocation.builder().chef(user).build());
        loc.setLatitude(latitude);
        loc.setLongitude(longitude);
        if (heading != null) {
            loc.setHeading(heading);
        }
        ChefLocation saved = locationRepository.save(loc);
        // The payload carries the request's heading (null when not sent), not the stored one - like Django.
        Runnable send = () -> broadcaster.broadcastLocation(user.getId(), latitude, longitude, heading);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public NearbyChefsResponse getNearbyChefs(double lat, double lng, double radiusKm) {
        List<ChefLocationRepository.NearbyRow> rows = locationRepository.findNearby(lat, lng, radiusKm);
        Map<Long, ChefLocation> byId = new LinkedHashMap<>();
        locationRepository.findAllById(rows.stream().map(ChefLocationRepository.NearbyRow::getId).toList())
                .forEach(l -> byId.put(l.getId(), l));
        List<NearbyChefItem> items = rows.stream().map(r -> {
            ChefLocation l = byId.get(r.getId());
            CustomUser chef = l.getChef();
            ChefProfile profile = chefProfileRepository.findByUserId(chef.getId()).orElse(null);
            String avatar = profile != null && profile.getAvatar() != null ? profile.getAvatar().getPublicUrl() : null;
            // PORT-NOTE: Django reads getattr(profile, "avg_rating", 0.0) but ChefProfile only has "rating",
            // so it is ALWAYS 0.0. Preserved by default (app.tracking.preserve-zero-avg-rating-bug).
            double rating = profile == null || preserveZeroAvgRatingBug ? 0.0 : profile.getRating();
            return new NearbyChefItem(chef.getId(), displayName(chef), avatar, l.getLatitude(), l.getLongitude(),
                    r.getDistanceKm(), rating, certificationProvider.isFoodSafetyCertified(chef.getId()));
        }).toList();
        Map<String, Double> center = new LinkedHashMap<>();
        center.put("lat", lat);
        center.put("lng", lng);
        return new NearbyChefsResponse(center, radiusKm, items);
    }

    /**
     * PORT-NOTE / Django bug (fixed by default): the endpoint does "from tracking.models import Order" - no such
     * name exists - so it raises ImportError (500) on EVERY call; it also declares order_id: int although Order's
     * pk is a UUID and reads non-existent order.delivery_lat/lng. Default here = the evident intent (order owned
     * by the caller, chef's stored location, order's delivery lat/lng). app.tracking.preserve-order-tracking-bug=true
     * = Django's permanent 500.
     */
    @Transactional(readOnly = true)
    public OrderTrackingResponse getOrderTracking(CustomUser customer, UUID orderId) {
        if (preserveOrderTrackingBug) {
            throw new IllegalStateException("ImportError: cannot import name 'Order' from 'tracking.models'");
        }
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getOwner() != null && o.getOwner().getId().equals(customer.getId()))
                .orElseThrow(() -> new TrackingNotFoundException("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        CustomUser chef = order.getChef();
        ChefLocation loc = chef == null ? null : locationRepository.findByChefId(chef.getId()).orElse(null);
        if (loc == null) {
            throw new TrackingNotFoundException("CHEF_LOCATION_NOT_FOUND", "Chưa có vị trí của đầu bếp.");
        }
        return new OrderTrackingResponse(order.getUid().toString(), order.getStatus().name(),
                new LocationPoint(loc.getLatitude(), loc.getLongitude()),
                new LocationPoint(order.getDeliveryLatitude(), order.getDeliveryLongitude()), displayName(chef));
    }

    /** Django get_full_name() or "Chef {id}". */
    static String displayName(CustomUser u) {
        String first = u.getFirstName() == null ? "" : u.getFirstName();
        String last = u.getLastName() == null ? "" : u.getLastName();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Chef " + u.getId() : full;
    }
}
