package com.amomeal.marketplace.chat.ws;

import com.amomeal.marketplace.security.InvalidOrExpiredTokenException;
import com.amomeal.marketplace.security.JwtService;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of ../../backend/chat/middleware.py::JWTAuthMiddleware + the auth half of {@code ChatConsumer.connect}:
 * the access token comes from the {@code ?token=} query parameter, is verified with the single {@link JwtService},
 * and the user must still exist. Anything else (no token, bad/expired token, unknown user) = Django's
 * {@code AnonymousUser} = the consumer closes BEFORE accepting, i.e. the handshake is refused (403).
 * The token's {@code exp} is stashed as a session attribute so the handler can force-close at that instant
 * (Django's {@code scope["token_exp"]}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "chat.userId";
    public static final String ATTR_ROOM_ID = "chat.roomId";
    public static final String ATTR_TOKEN_EXP = "chat.tokenExp";

    /** Django route {@code ws/chat/(?P<room_id>\w+)/$}. */
    private static final Pattern PATH = Pattern.compile("^/ws/chat/([\\p{L}\\p{N}_]+)/$");

    private final JwtService jwtService;
    private final CustomUserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        Matcher m = PATH.matcher(request.getURI().getRawPath());
        if (!m.matches()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        if (token == null || token.isEmpty()) {
            log.info("Đuổi một kẻ không có Token ra khỏi cửa");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            Long userId = jwtService.decodeAccessToken(URLDecoder.decode(token, StandardCharsets.UTF_8));
            if (!userRepository.existsById(userId)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put(ATTR_USER_ID, userId);
            attributes.put(ATTR_ROOM_ID, m.group(1));
            Long exp = readExp(token);
            if (exp != null) {
                attributes.put(ATTR_TOKEN_EXP, exp);
            }
            return true;
        } catch (InvalidOrExpiredTokenException ex) {
            log.info("JWT Decode Error: {}", ex.toString());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // nothing
    }

    /** {@code exp} claim of an already signature-verified token (payload segment only, no re-verification). */
    private Long readExp(String token) {
        try {
            String[] parts = token.split("\\.");
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = payload.get("exp");
            return exp == null || exp.isNull() ? null : exp.asLong();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
