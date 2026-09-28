package com.amomeal.marketplace.tracking.ws;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.security.JwtService;
import com.amomeal.marketplace.tracking.repository.ChefLocationRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Tomcat on a random port + StandardWebSocketClient: REST location update -> persisted + pushed to watchers. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrackingWebSocketTest {

    @LocalServerPort int port;
    @Autowired JwtService jwtService;
    @Autowired CustomUserRepository userRepository;
    @Autowired ChefLocationRepository locationRepository;
    @Autowired TrackingWebSocketHandler handler;
    @Autowired ObjectMapper objectMapper;

    static class Client extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        volatile WebSocketSession session;

        @Override
        public void afterConnectionEstablished(WebSocketSession s) {
            session = s;
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage m) {
            frames.add(m.getPayload());
        }
    }

    private CustomUser chef(String prefix) {
        String nonce = String.valueOf(System.nanoTime());
        CustomUser u = CustomUser.builder().username(prefix + nonce).email(prefix + nonce + "@amomeal.test")
                .password("x").build();
        u.addRole(UserRole.CHEF);
        return userRepository.save(u);
    }

    /** No credentials of any kind: the Django consumer has no auth. */
    private Client watch(long chefId) throws Exception {
        Client c = new Client();
        new StandardWebSocketClient().execute(c, null, URI.create("ws://localhost:" + port + "/ws/tracking/chef/" + chefId + "/"))
                .get(10, TimeUnit.SECONDS);
        return c;
    }

    private void postLocation(CustomUser chef, String json) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/tracking/chef/location"))
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(chef.getId()))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(r.body()).get("data").get("success").asBoolean()).isTrue();
    }

    private JsonNode next(Client c) throws Exception {
        String f = c.frames.poll(10, TimeUnit.SECONDS);
        assertThat(f).as("a frame within 10s").isNotNull();
        return objectMapper.readTree(f);
    }

    @Test
    void locationUpdate_isPersisted_andPushedOnlyToWatchersOfThatChef_withoutAuth() throws Exception {
        CustomUser chefA = chef("wta");
        CustomUser chefB = chef("wtb");
        Client watcher1 = watch(chefA.getId());
        Client watcher2 = watch(chefA.getId());
        Client otherChefWatcher = watch(chefB.getId());
        assertThat(handler.connectionCount(chefA.getId())).isEqualTo(2);

        postLocation(chefA, "{\"latitude\":10.5,\"longitude\":106.25,\"heading\":90.0}");

        for (Client w : new Client[]{watcher1, watcher2}) {
            JsonNode n = next(w);
            assertThat(n.get("action").asString()).isEqualTo("location_update");
            JsonNode d = n.get("data");
            assertThat(d.get("latitude").asDouble()).isEqualTo(10.5);
            assertThat(d.get("longitude").asDouble()).isEqualTo(106.25);
            assertThat(d.get("heading").asDouble()).isEqualTo(90.0);
            assertThat(d.get("chef_id").asLong()).isEqualTo(chefA.getId());
        }
        assertThat(otherChefWatcher.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();

        var row = locationRepository.findByChefId(chefA.getId()).orElseThrow();
        assertThat(row.getLatitude()).isEqualTo(10.5);
        assertThat(row.getHeading()).isEqualTo(90.0);
        assertThat(locationRepository.findByChefId(chefB.getId())).isEmpty();

        // second update without heading: heading is null in the push, previous heading stays in the DB
        postLocation(chefA, "{\"latitude\":11.0,\"longitude\":107.0}");
        JsonNode second = next(watcher1);
        assertThat(second.get("data").get("heading").isNull()).isTrue();
        assertThat(locationRepository.findByChefId(chefA.getId()).orElseThrow().getHeading()).isEqualTo(90.0);
        next(watcher2);

        // client frames are ignored (no receive() in the Django consumer); chef B's update reaches only B's watcher
        watcher1.session.sendMessage(new TextMessage("{\"anything\":1}"));
        postLocation(chefB, "{\"latitude\":1.0,\"longitude\":2.0}");
        assertThat(next(otherChefWatcher).get("data").get("chef_id").asLong()).isEqualTo(chefB.getId());
        assertThat(watcher1.frames.poll(500, TimeUnit.MILLISECONDS)).isNull();

        for (Client c : new Client[]{watcher1, watcher2, otherChefWatcher}) {
            c.session.close();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (handler.connectionCount(chefA.getId()) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(handler.connectionCount(chefA.getId())).isZero();
    }

    @Test
    void nonNumericChefId_isRejected() {
        assertThatThrownBy(() -> new StandardWebSocketClient().execute(new Client(), null,
                URI.create("ws://localhost:" + port + "/ws/tracking/chef/abc/")).get(10, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }
}
