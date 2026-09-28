package com.amomeal.marketplace.chat.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.chat.service.ChatService;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full-stack (Postgres/Redis Testcontainers) tests of the RAW (non-envelope) DRF-style chat REST endpoints. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomUserRepository userRepository;
    @Autowired ChatService chatService;

    record Account(String token, CustomUser user) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0901234567"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        CustomUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        user = userRepository.save(user);
        return new Account(objectMapper.readTree(json).get("data").get("access_token").asString(), user);
    }

    private JsonNode json(String s) throws Exception {
        return objectMapper.readTree(s);
    }

    @Test
    void unauthenticated_isRejected401() throws Exception {
        mockMvc.perform(get("/api/chat/conversations/")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/chat/1/messages/")).andExpect(status().isUnauthorized());
    }

    @Test
    void emptyList_isARawEmptyArray_notAnEnvelope() throws Exception {
        Account a = register("lonely", UserRole.CUSTOMER);
        String body = mockMvc.perform(get("/api/chat/conversations/").header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("[]");
    }

    @Test
    void createListAndHistory_rawShapes_andNewestFirst() throws Exception {
        Account customer = register("cus", UserRole.CUSTOMER);
        Account chef = register("chef", UserRole.CHEF);

        String created = mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("{\"chef_id\":" + chef.user().getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room_id").isNumber())
                .andExpect(jsonPath("$.message_code").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.partner_id").value(chef.user().getId()))
                .andExpect(jsonPath("$.latest_message").value("Bắt đầu cuộc trò chuyện..."))
                .andReturn().getResponse().getContentAsString();
        long room = json(created).get("room_id").asLong();

        // idempotent get-or-create
        mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("{\"chef_id\":" + chef.user().getId() + "}"))
                .andExpect(jsonPath("$.room_id").value(room));

        assertThat(chatService.saveMessage(customer.user().getId(), String.valueOf(room), "first")).isTrue();
        Thread.sleep(5);
        assertThat(chatService.saveMessage(chef.user().getId(), String.valueOf(room), "second")).isTrue();

        mockMvc.perform(get("/api/chat/conversations/").header("Authorization", chef.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].room_id").value(room))
                .andExpect(jsonPath("$[0].partner_id").value(customer.user().getId()))
                .andExpect(jsonPath("$[0].partner_name").value(customer.user().getUsername()))
                .andExpect(jsonPath("$[0].latest_message").value("second"))
                .andExpect(jsonPath("$[0].updated_at").value(org.hamcrest.Matchers.endsWith("+00:00")));

        mockMvc.perform(get("/api/chat/" + room + "/messages/").header("Authorization", customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].message").value("second"))
                .andExpect(jsonPath("$[0].sender_id").value(chef.user().getId()))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].created_at").isString())
                .andExpect(jsonPath("$[1].message").value("first"));
    }

    @Test
    void history_nonParticipant403_unknownRoom404_rawErrorBodies() throws Exception {
        Account customer = register("c2", UserRole.CUSTOMER);
        Account chef = register("h2", UserRole.CHEF);
        Account stranger = register("s2", UserRole.CUSTOMER);
        String created = mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("{\"chef_id\":" + chef.user().getId() + "}"))
                .andReturn().getResponse().getContentAsString();
        long room = json(created).get("room_id").asLong();

        mockMvc.perform(get("/api/chat/" + room + "/messages/").header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Bạn không có quyền xem phòng chat này"))
                .andExpect(jsonPath("$.message_code").doesNotExist());
        mockMvc.perform(get("/api/chat/999999999/messages/").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Phòng chat không tồn tại"));
        // a stranger's own list never contains the room
        mockMvc.perform(get("/api/chat/conversations/").header("Authorization", stranger.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void history_nonNumericRoomId_isServerError_likeDjangoValueError() throws Exception {
        Account a = register("nn", UserRole.CUSTOMER);
        mockMvc.perform(get("/api/chat/abc/messages/").header("Authorization", a.bearer()))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void create_validation_targetMustBeChef_notSelf_bodyRequired() throws Exception {
        Account customer = register("v1", UserRole.CUSTOMER);
        Account other = register("v2", UserRole.CUSTOMER);
        mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("{\"chef_id\":" + other.user().getId() + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("{\"chef_id\":" + customer.user().getId() + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/chat/conversations/").header("Authorization", customer.bearer())
                        .contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
    }
}
