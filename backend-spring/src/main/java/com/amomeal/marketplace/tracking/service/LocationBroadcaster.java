package com.amomeal.marketplace.tracking.service;

/** Django: {@code channel_layer.group_send("tracking_chef_<id>", {"type": "send_location_update", "data": ...})}. */
public interface LocationBroadcaster {

    void broadcastLocation(long chefId, double latitude, double longitude, Double heading);
}
