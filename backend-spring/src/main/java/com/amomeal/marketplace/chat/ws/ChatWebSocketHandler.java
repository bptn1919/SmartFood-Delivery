package com.amomeal.marketplace.chat.ws;

import com.amomeal.marketplace.chat.service.ChatService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Port of ../../backend/chat/consumers.py::ChatConsumer (+ {@code utils/channels/jwt_expiry.py}).
 *
 * <p>Protocol (identical to Django):
 * <ul>
 *   <li>URL {@code /ws/chat/<room_id>/?token=<access JWT>}; the "group" is {@code chat_<room_id>}; connecting = join,
 *       disconnecting = leave (there are no explicit join/leave frames in Django).</li>
 *   <li>Client to server: {@code {"message": <text>}} (any {@code sender_id} the client sends is ignored, the
 *       sender is the authenticated user). Invalid JSON is ignored (Django only logs it); valid JSON without a
 *       {@code message} key (or not an object) crashes the consumer in Django, so the socket is closed (1011) here.</li>
 *   <li>The message is persisted (failures swallowed; NO participant check, any authenticated user can join any
 *       room and post to it) and THEN broadcast to every socket in the group, sender included, as
 *       {@code {"message": <text>, "sender_id": <id>}} — broadcast happens even when the save failed.</li>
 *   <li>The socket is force-closed with code 4001 exactly when the token's {@code exp} arrives, whether or not
 *       messages are flowing (a {@link ThreadPoolTaskScheduler} task per connection, cancelled on disconnect).</li>
 * </ul>
 *
 * <p>LIMITATION: the group registry is in-memory (single instance). Django fanned out through a Redis channel layer,
 * so it worked across several server processes; here two instances would not see each other's sockets.
 */
@Component
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** Custom close code: token expired, reconnect with a fresh access token (same as Django). */
    public static final int CLOSE_TOKEN_EXPIRED = 4001;

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskScheduler scheduler;

    /** group name -> (session id -> thread-safe session). */
    private final Map<String, Map<String, WebSocketSession>> groups = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        // Private to this handler on purpose: declaring a TaskScheduler @Bean would displace Boot's default one
        // that the other modules' @Scheduled jobs use.
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(1);
        this.scheduler.setThreadNamePrefix("chat-ws-expiry-");
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.initialize();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    static String groupName(String roomId) {
        return "chat_" + roomId;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        String room = (String) raw.getAttributes().get(ChatHandshakeInterceptor.ATTR_ROOM_ID);
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
        groups.computeIfAbsent(groupName(room), k -> new ConcurrentHashMap<>()).put(raw.getId(), session);
        Object exp = raw.getAttributes().get(ChatHandshakeInterceptor.ATTR_TOKEN_EXP);
        if (exp instanceof Long expSeconds && expSeconds > 0) {
            expiryTasks.put(raw.getId(), scheduler.schedule(() -> expire(session), Instant.ofEpochSecond(expSeconds)));
        }
        log.info("User connected to room: {}", groupName(room));
    }

    private void expire(WebSocketSession session) {
        try {
            session.close(new CloseStatus(CLOSE_TOKEN_EXPIRED, "token expired"));
        } catch (IOException | RuntimeException e) {
            log.debug("expiry close failed: {}", e.toString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ScheduledFuture<?> task = expiryTasks.remove(session.getId());
        if (task != null) {
            task.cancel(false);
        }
        String group = groupName((String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_ROOM_ID));
        Map<String, WebSocketSession> members = groups.get(group);
        if (members != null) {
            members.remove(session.getId());
            if (members.isEmpty()) {
                groups.remove(group, members);
            }
        }
        log.info("User disconnected from room: {}", group);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws IOException {
        String room = (String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_ROOM_ID);
        Long senderId = (Long) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        JsonNode data;
        try {
            data = objectMapper.readTree(textMessage.getPayload());
        } catch (JacksonException e) {
            log.warn("Client gửi sai format JSON.");
            return;
        }
        if (data == null || !data.isObject() || !data.has("message")) {
            // Django: KeyError / TypeError inside receive() -> the consumer dies and the socket is dropped.
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        Object message = objectMapper.treeToValue(data.get("message"), Object.class);
        chatService.saveMessage(senderId, room, message);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("message", message);
        event.put("sender_id", senderId);
        broadcast(groupName(room), objectMapper.writeValueAsString(event));
    }

    private void broadcast(String group, String json) {
        Map<String, WebSocketSession> members = groups.get(group);
        if (members == null) {
            return;
        }
        for (WebSocketSession s : members.values()) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(new TextMessage(json));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("broadcast to {} failed: {}", s.getId(), e.toString());
            }
        }
    }

    /** Test/diagnostic hook: number of live sockets in a room. */
    public int connectionCount(String roomId) {
        Map<String, WebSocketSession> members = groups.get(groupName(roomId));
        return members == null ? 0 : members.size();
    }
}
