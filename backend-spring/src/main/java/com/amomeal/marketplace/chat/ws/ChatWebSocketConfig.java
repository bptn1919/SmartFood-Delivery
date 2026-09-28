package com.amomeal.marketplace.chat.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Django {@code chat/routing.py}: {@code ws/chat/<room_id>/}. Auth is the handshake interceptor (SecurityConfig already
 * permits {@code /ws/**}). Origins are left open, like the Django ASGI stack has no origin validator on this route.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final ChatHandshakeInterceptor interceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat/*/")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
