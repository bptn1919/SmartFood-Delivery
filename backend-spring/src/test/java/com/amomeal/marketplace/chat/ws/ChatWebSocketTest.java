package com.amomeal.marketplace.chat.ws;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.chat.entity.Conversation;
import com.amomeal.marketplace.chat.repository.ConversationRepository;
import com.amomeal.marketplace.chat.repository.MessageRepository;
import com.amomeal.marketplace.security.JwtService;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real websocket tests: an actual Tomcat on a random port, Spring's StandardWebSocketClient. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketTest {

    @LocalServerPort int port;
    @Autowired JwtService jwtService;
    @Autowired CustomUserRepository userRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatWebSocketHandler handler;
    @Autowired ObjectMapper objectMapper;
    @Value("${app.auth.jwt-secret}") String jwtSecret;

    CustomUser customer;
    CustomUser chef;
    Conversation room;

    /** Client end of a socket: queues text frames, remembers the close status. */
    static class Client extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        volatile WebSocketSession session;

        @Override
        public void afterConnectionEstablished(WebSocketSession s) {
            session = s;
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage m) {
            frames.add(m.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }
    }

    @BeforeEach
    void setUp() {
        customer = user("wsc", UserRole.CUSTOMER);
        chef = user("wsh", UserRole.CHEF);
        room = conversationRepository.save(Conversation.builder().customer(customer).chef(chef).build());
    }

    private CustomUser user(String prefix, UserRole role) {
        String nonce = String.valueOf(System.nanoTime());
        CustomUser u = CustomUser.builder().username(prefix + nonce).email(prefix + nonce + "@amomeal.test")
                .password("x").build();
        u.addRole(role);
        return userRepository.save(u);
    }

    private Client connect(String roomId, String token) throws Exception {
        Client c = new Client();
        new StandardWebSocketClient()
                .execute(c, null, URI.create("ws://localhost:" + port + "/ws/chat/" + roomId + "/?token=" + token))
                .get(10, TimeUnit.SECONDS);
        return c;
    }

    private Client connect(CustomUser u) throws Exception {
        return connect(String.valueOf(room.getId()), jwtService.issueAccessToken(u.getId()));
    }

    private String next(Client c) throws Exception {
        String f = c.frames.poll(10, TimeUnit.SECONDS);
        assertThat(f).as("a frame within 10s").isNotNull();
        return f;
    }

    @Test
    void message_isPersisted_andBroadcastToBothParticipants_includingSender() throws Exception {
        Client a = connect(customer);
        Client b = connect(chef);
        assertThat(handler.connectionCount(String.valueOf(room.getId()))).isEqualTo(2);

        // a fake sender_id from the client must be ignored: the authenticated user wins
        a.session.sendMessage(new TextMessage("{\"message\":\"xin chào\",\"sender_id\":" + chef.getId() + "}"));

        JsonNode toB = objectMapper.readTree(next(b));
        JsonNode toA = objectMapper.readTree(next(a));
        for (JsonNode n : new JsonNode[]{toA, toB}) {
            assertThat(n.get("message").asString()).isEqualTo("xin chào");
            assertThat(n.get("sender_id").asLong()).isEqualTo(customer.getId());
            assertThat(n.size()).isEqualTo(2);
        }
        var saved = messageRepository.findTop50ByConversationIdOrderByCreatedAtDescIdDesc(room.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getText()).isEqualTo("xin chào");
        assertThat(saved.get(0).getSender().getId()).isEqualTo(customer.getId());

        // reply the other way
        b.session.sendMessage(new TextMessage("{\"message\":\"chào bạn\"}"));
        assertThat(objectMapper.readTree(next(a)).get("sender_id").asLong()).isEqualTo(chef.getId());

        a.session.close();
        b.session.close();
        a.closed.get(5, TimeUnit.SECONDS);
        b.closed.get(5, TimeUnit.SECONDS);
        assertThat(waitForCount(String.valueOf(room.getId()), 0)).isTrue();
    }

    @Test
    void roomsAreIsolated_andInvalidJsonIsIgnored_missingMessageKeyDropsTheSocket() throws Exception {
        Conversation other = conversationRepository.save(Conversation.builder().customer(customer).chef(user("oth", UserRole.CHEF)).build());
        Client inRoom = connect(customer);
        Client elsewhere = connect(String.valueOf(other.getId()), jwtService.issueAccessToken(customer.getId()));

        inRoom.session.sendMessage(new TextMessage("this is not json"));
        inRoom.session.sendMessage(new TextMessage("{\"message\":\"still alive\"}"));
        assertThat(objectMapper.readTree(next(inRoom)).get("message").asString()).isEqualTo("still alive");
        assertThat(elsewhere.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();

        inRoom.session.sendMessage(new TextMessage("{\"nope\":1}"));
        assertThat(inRoom.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        elsewhere.session.close();
    }

    @Test
    void anyAuthenticatedUserCanJoinAndPost_noParticipantCheck_likeDjango() throws Exception {
        CustomUser stranger = user("nosy", UserRole.CUSTOMER);
        Client intruder = connect(stranger);
        Client a = connect(customer);
        intruder.session.sendMessage(new TextMessage("{\"message\":\"psst\"}"));
        assertThat(objectMapper.readTree(next(a)).get("sender_id").asLong()).isEqualTo(stranger.getId());
        intruder.session.close();
        a.session.close();
    }

    @Test
    void unknownRoom_broadcastStillHappens_evenThoughSaveFailedSilently() throws Exception {
        Client c = connect("987654321", jwtService.issueAccessToken(customer.getId()));
        c.session.sendMessage(new TextMessage("{\"message\":\"into the void\"}"));
        assertThat(objectMapper.readTree(next(c)).get("message").asString()).isEqualTo("into the void");
        assertThat(messageRepository.findTop50ByConversationIdOrderByCreatedAtDescIdDesc(987654321L)).isEmpty();
        c.session.close();
    }

    @Test
    void invalidOrMissingToken_isRejectedAtHandshake() {
        String id = String.valueOf(room.getId());
        assertThatThrownBy(() -> connect(id, "garbage")).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> connect(id, "")).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new Client(), null, URI.create("ws://localhost:" + port + "/ws/chat/" + id + "/"))
                .get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        // a token for a user that no longer exists
        String ghost = jwtService.issueAccessToken(987654321L);
        assertThatThrownBy(() -> connect(id, ghost)).isInstanceOf(ExecutionException.class);
        // wrong signature
        String forged = Jwts.builder().claim("user_id", customer.getId()).claim("typ", "access")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThatThrownBy(() -> connect(id, forged)).isInstanceOf(ExecutionException.class);
        // refresh-type token
        String wrongType = Jwts.builder().claim("user_id", customer.getId()).claim("typ", "refresh")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
        assertThatThrownBy(() -> connect(id, wrongType)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void alreadyExpiredToken_isRejected() {
        String expired = shortLivedToken(customer.getId(), -5);
        assertThatThrownBy(() -> connect(String.valueOf(room.getId()), expired)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void socketIsForceClosedAtTokenExp_withCode4001_evenWhenIdle() throws Exception {
        String token = shortLivedToken(customer.getId(), 3);
        long start = System.nanoTime();
        Client idle = connect(String.valueOf(room.getId()), token);
        CloseStatus status = idle.closed.get(15, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(status.getCode()).isEqualTo(4001);
        assertThat(elapsedMs).isLessThan(8000);
        assertThat(waitForCount(String.valueOf(room.getId()), 0)).isTrue();
    }

    private String shortLivedToken(Long userId, long secondsFromNow) {
        Instant now = Instant.now();
        return Jwts.builder().claim("user_id", userId).claim("typ", "access")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(now.plusSeconds(secondsFromNow)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private boolean waitForCount(String roomId, int expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (handler.connectionCount(roomId) == expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
