package com.amomeal.marketplace.chat.web;

import com.amomeal.marketplace.chat.service.ChatRawException;
import com.amomeal.marketplace.chat.service.ChatService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Port of ../../backend/chat/{views,urls}.py mounted at {@code api/chat/}. These are plain DRF views, NOT ninja:
 * the responses are RAW JSON (a bare list on success, {@code {"error": "..."}} on 403/404), with no
 * {@code {data, message_code, ...}} envelope and their real HTTP statuses. Same mechanism as
 * {@code payment.web.PayOsCallbackController}: the methods return {@code void} and write the servlet response
 * themselves, so {@code ResponseEnvelopeAdvice} never sees a body.
 *
 * <p>Known difference: an unauthenticated / bad-token request is rejected earlier by Spring Security with the
 * project's standard 401 envelope, whereas DRF answers {@code {"detail": "..."}}; FE-admin has no chat screen,
 * so this is not replicated.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /** Django {@code GET /api/chat/conversations/}. */
    @GetMapping({"/conversations/", "/conversations"})
    public void conversations(@AuthenticationPrincipal CustomUser user, HttpServletResponse response) throws IOException {
        write(response, 200, chatService.listConversations(user));
    }

    /** Django {@code GET /api/chat/<room_id>/messages/}. */
    @GetMapping({"/{roomId}/messages/", "/{roomId}/messages"})
    public void history(@AuthenticationPrincipal CustomUser user, @PathVariable String roomId,
                        HttpServletResponse response) throws IOException {
        try {
            write(response, 200, chatService.history(user, roomId));
        } catch (ChatRawException ex) {
            write(response, ex.getStatus(), ex.getBody());
        }
    }

    /**
     * NOT in Django (see {@link ChatService} flag {@code app.chat.preserve-no-conversation-create-bug}): body
     * {@code {"chef_id": N}} get-or-creates the caller's conversation with that chef; always 200 with the
     * conversation summary shape of the list endpoint.
     */
    @PostMapping({"/conversations/", "/conversations"})
    public void createConversation(@AuthenticationPrincipal CustomUser user, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        if (!chatService.isConversationCreateEnabled()) {
            write(response, 405, Map.of("detail", "Method \"POST\" not allowed."));
            return;
        }
        try {
            JsonNode body = objectMapper.readTree(request.getInputStream());
            JsonNode chefId = body == null ? null : body.get("chef_id");
            if (chefId == null || chefId.isNull()) {
                throw ChatRawException.error(400, "chef_id là bắt buộc");
            }
            write(response, 200, chatService.getOrCreateConversation(user, chefId.asString()));
        } catch (ChatRawException ex) {
            write(response, ex.getStatus(), ex.getBody());
        } catch (tools.jackson.core.JacksonException ex) {
            write(response, 400, Map.of("error", "JSON không hợp lệ"));
        }
    }

    private void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
    }
}
