package com.amomeal.marketplace.tracking.ws;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the numeric chef_id from /ws/tracking/chef/CHEF_ID/ (Django: int converter). NO auth, like Django. */
@Component
public class TrackingHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern PATH = Pattern.compile("/ws/tracking/chef/(\\d{1,18})/?$");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        Matcher m = PATH.matcher(request.getURI().getPath());
        if (!m.find()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        attributes.put(TrackingWebSocketHandler.ATTR_CHEF_ID, m.group(1).replaceFirst("^0+(?=.)", ""));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
