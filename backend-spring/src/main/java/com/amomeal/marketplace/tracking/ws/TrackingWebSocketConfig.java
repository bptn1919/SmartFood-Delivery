package com.amomeal.marketplace.tracking.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Tracking's own route (Django mounted it through mongo_chat/routing.py, CLAUDE.md 0.4). SecurityConfig permits /ws/**.
 *
 * <p>PORT-NOTE / Django bug (fixed by default): the Django route is re_path('ws/tracking/chef/INT_CONVERTER/', ...)
 * - a path-converter string used as a REGEX (and with no named group), so it never matches a real URL and even a match
 * would KeyError on chef_id: the live-tracking socket can never connect. Default = a working route.
 * app.tracking.preserve-ws-route-bug=true registers nothing (connecting 404s, like Django).
 */
@Configuration
@ConditionalOnProperty(name = "app.tracking.preserve-ws-route-bug", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class TrackingWebSocketConfig implements WebSocketConfigurer {

    private final TrackingWebSocketHandler handler;
    private final TrackingHandshakeInterceptor interceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/tracking/chef/*/")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
