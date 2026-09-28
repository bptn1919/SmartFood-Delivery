package com.amomeal.marketplace.chat.service;

import com.amomeal.marketplace.chat.entity.Conversation;
import com.amomeal.marketplace.chat.entity.Message;
import com.amomeal.marketplace.chat.repository.ConversationRepository;
import com.amomeal.marketplace.chat.repository.MessageRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of ../../backend/chat/views.py + consumers.py::save_message. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    public static final String EMPTY_PREVIEW = "Bắt đầu cuộc trò chuyện...";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final CustomUserRepository userRepository;

    /**
     * PORT-NOTE (CLAUDE.md 0.6): nothing in Django ever creates a {@code Conversation} except the Django admin
     * site, so chat can never start for real users. Default = working (POST /api/chat/conversations/ get-or-creates);
     * {@code true} = Django (the route does not exist, 405).
     */
    @Value("${app.chat.preserve-no-conversation-create-bug:false}")
    private boolean preserveNoConversationCreateBug;

    public boolean isConversationCreateEnabled() {
        return !preserveNoConversationCreateBug;
    }

    /** Django {@code ConversationListView.get}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConversations(CustomUser user) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Conversation conv : conversationRepository.findAllOfUser(user.getId())) {
            result.add(summary(conv, user));
        }
        return result;
    }

    /**
     * Django {@code ChatHistoryView.get}: 404 if the room does not exist, 403 unless customer or chef, the 50 NEWEST
     * messages (descending, i.e. newest first, despite the model's ascending Meta.ordering).
     * A non-numeric room id is a Django ValueError (500): {@link NumberFormatException} propagates the same way.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(CustomUser user, String roomId) {
        Long id = Long.valueOf(roomId);
        Conversation conv = conversationRepository.findWithUsersById(id)
                .orElseThrow(() -> ChatRawException.error(404, "Phòng chat không tồn tại"));
        if (!user.getId().equals(conv.getCustomer().getId()) && !user.getId().equals(conv.getChef().getId())) {
            throw ChatRawException.error(403, "Bạn không có quyền xem phòng chat này");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : messageRepository.findTop50ByConversationIdOrderByCreatedAtDescIdDesc(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("sender_id", m.getSender().getId());
            row.put("message", m.getText());
            row.put("created_at", drfIso(m.getCreatedAt()));
            out.add(row);
        }
        return out;
    }

    /** The (non-Django) creation route that fixes "chat can never start". Caller = customer, target = a CHEF. */
    @Transactional
    public Map<String, Object> getOrCreateConversation(CustomUser caller, Object chefIdRaw) {
        Long chefId;
        try {
            chefId = Long.valueOf(String.valueOf(chefIdRaw));
        } catch (NumberFormatException ex) {
            throw ChatRawException.error(400, "chef_id không hợp lệ");
        }
        if (chefId.equals(caller.getId())) {
            throw ChatRawException.error(400, "Không thể tự trò chuyện với chính mình");
        }
        CustomUser chef = userRepository.findById(chefId)
                .filter(u -> u.hasRole(UserRole.CHEF))
                .orElseThrow(() -> ChatRawException.error(404, "Không tìm thấy đầu bếp"));
        Conversation conv = conversationRepository.findByPair(caller.getId(), chef.getId()).orElse(null);
        if (conv == null) {
            conv = conversationRepository.save(Conversation.builder().customer(caller).chef(chef).build());
        }
        return summary(conv, caller);
    }

    /**
     * Django {@code consumers.save_message}: any failure (unknown room, unknown user, non-numeric room id, null text)
     * is swallowed and logged; NOTHING checks that the sender belongs to the room. Returns whether it was saved.
     */
    public boolean saveMessage(Long senderId, String roomId, Object text) {
        try {
            Conversation conv = conversationRepository.findById(Long.valueOf(roomId)).orElseThrow();
            CustomUser sender = userRepository.findById(senderId).orElseThrow();
            if (text == null) {
                throw new IllegalArgumentException("text is NOT NULL");
            }
            messageRepository.saveAndFlush(Message.builder().conversation(conv).sender(sender)
                    .text(String.valueOf(text)).build());
            return true;
        } catch (Exception e) {
            log.warn("Lỗi lưu Database: {}", e.toString());
            return false;
        }
    }

    private Map<String, Object> summary(Conversation conv, CustomUser user) {
        CustomUser partner = conv.getCustomer().getId().equals(user.getId()) ? conv.getChef() : conv.getCustomer();
        Message latest = messageRepository.findFirstByConversationIdOrderByCreatedAtDescIdDesc(conv.getId()).orElse(null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("room_id", conv.getId());
        row.put("partner_id", partner.getId());
        // Django: partner.fullname if hasattr(...) else username; the user model has no `fullname`.
        row.put("partner_name", partner.getUsername());
        row.put("latest_message", latest != null ? latest.getText() : EMPTY_PREVIEW);
        row.put("updated_at", pyIso(conv.getUpdatedAt()));
        return row;
    }

    /** Python {@code datetime.isoformat()} for an aware UTC value: {@code ...+00:00}. */
    static String pyIso(Instant t) {
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atOffset(ZoneOffset.UTC)).replace("Z", "+00:00");
    }

    /** DRF DateTimeField in UTC: {@code ...Z}. */
    static String drfIso(Instant t) {
        return t.toString();
    }
}
