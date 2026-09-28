package com.amomeal.marketplace.tracking.ws;

import com.amomeal.marketplace.tracking.service.LocationBroadcaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Port of ../../backend/tracking/consumers.py::TrackingConsumer.
 *
 * <p>Protocol (identical to Django): URL /ws/tracking/chef/CHEF_ID/; connecting joins group
 * tracking_chef_CHEF_ID, disconnecting leaves it. The socket is receive-only - client frames are ignored
 * (the consumer defines no receive). Every chef location update (REST POST /api/tracking/chef/location)
 * is pushed to the group as {"action":"location_update","data":{"latitude","longitude","heading","chef_id"}}
 * (heading is null when the chef did not send one).
 *
 * <p>PORT-NOTE: NO authentication (Django's consumer has none) - anyone can watch any chef's live location.
 * LIMITATION: the registry is in-memory (single instance), Django fanned out via Redis.
 */
@Component
@Slf4j
public class TrackingWebSocketHandler extends TextWebSocketHandler implements LocationBroadcaster {

    static final String ATTR_CHEF_ID = "trackingChefId";

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, WebSocketSession>> groups = new ConcurrentHashMap<>();

    public TrackingWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static String groupName(String chefId) {
        return "tracking_chef_" + chefId;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        String chefId = (String) raw.getAttributes().get(ATTR_CHEF_ID);
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
        groups.computeIfAbsent(groupName(chefId), k -> new ConcurrentHashMap<>()).put(raw.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String group = groupName((String) session.getAttributes().get(ATTR_CHEF_ID));
        Map<String, WebSocketSession> members = groups.get(group);
        if (members != null) {
            members.remove(session.getId());
            if (members.isEmpty()) {
                groups.remove(group, members);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Django's consumer has no receive(): inbound frames are dropped.
    }

    @Override
    public void broadcastLocation(long chefId, double latitude, double longitude, Double heading) {
        Map<String, WebSocketSession> members = groups.get(groupName(String.valueOf(chefId)));
        if (members == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latitude", latitude);
        data.put("longitude", longitude);
        data.put("heading", heading);
        data.put("chef_id", chefId);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", "location_update");
        event.put("data", data);
        TextMessage frame = new TextMessage(objectMapper.writeValueAsString(event));
        for (WebSocketSession s : members.values()) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(frame);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("tracking broadcast to {} failed: {}", s.getId(), e.toString());
            }
        }
    }

    /** Test/diagnostic hook. */
    public int connectionCount(long chefId) {
        Map<String, WebSocketSession> members = groups.get(groupName(String.valueOf(chefId)));
        return members == null ? 0 : members.size();
    }
}
