package com.amomeal.marketplace.chat.service;

import com.amomeal.marketplace.chat.entity.Conversation;
import com.amomeal.marketplace.chat.entity.Message;
import com.amomeal.marketplace.chat.repository.ConversationRepository;
import com.amomeal.marketplace.chat.repository.MessageRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ConversationRepository conversations;
    @Mock MessageRepository messages;
    @Mock CustomUserRepository users;
    @InjectMocks ChatService service;

    CustomUser customer;
    CustomUser chef;
    CustomUser stranger;
    Conversation conv;

    @BeforeEach
    void setUp() {
        customer = CustomUser.builder().id(1L).username("cus").build();
        chef = CustomUser.builder().id(2L).username("chefy").build();
        chef.addRole(UserRole.CHEF);
        stranger = CustomUser.builder().id(3L).username("nosy").build();
        conv = Conversation.builder().id(10L).customer(customer).chef(chef)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z")).updatedAt(Instant.parse("2026-01-01T00:00:00Z")).build();
    }

    @Test
    void list_partnerIsTheOtherSide_previewFallbackAndPythonIso() {
        when(conversations.findAllOfUser(1L)).thenReturn(List.of(conv));
        when(messages.findFirstByConversationIdOrderByCreatedAtDescIdDesc(10L)).thenReturn(Optional.empty());

        List<Map<String, Object>> out = service.listConversations(customer);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("room_id", 10L).containsEntry("partner_id", 2L)
                .containsEntry("partner_name", "chefy")
                .containsEntry("latest_message", "Bắt đầu cuộc trò chuyện...")
                .containsEntry("updated_at", "2026-01-01T00:00:00+00:00");
    }

    @Test
    void list_forChef_partnerIsCustomer_andLatestMessageIsUsed() {
        when(conversations.findAllOfUser(2L)).thenReturn(List.of(conv));
        when(messages.findFirstByConversationIdOrderByCreatedAtDescIdDesc(10L))
                .thenReturn(Optional.of(Message.builder().id(5L).conversation(conv).sender(customer).text("hi").build()));

        Map<String, Object> row = service.listConversations(chef).get(0);

        assertThat(row).containsEntry("partner_id", 1L).containsEntry("partner_name", "cus")
                .containsEntry("latest_message", "hi");
    }

    @Test
    void history_unknownRoom_is404_withRawErrorBody() {
        when(conversations.findWithUsersById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(customer, "99"))
                .isInstanceOfSatisfying(ChatRawException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getBody()).containsEntry("error", "Phòng chat không tồn tại");
                });
    }

    @Test
    void history_nonParticipant_is403() {
        when(conversations.findWithUsersById(10L)).thenReturn(Optional.of(conv));

        assertThatThrownBy(() -> service.history(stranger, "10"))
                .isInstanceOfSatisfying(ChatRawException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(403);
                    assertThat(e.getBody()).containsEntry("error", "Bạn không có quyền xem phòng chat này");
                });
        verify(messages, never()).findTop50ByConversationIdOrderByCreatedAtDescIdDesc(any());
    }

    @Test
    void history_nonNumericRoom_isAServerError_likeDjangoValueError() {
        assertThatThrownBy(() -> service.history(customer, "abc")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void history_returnsMessageShape_newestFirstAsRepositoryOrders() {
        when(conversations.findWithUsersById(10L)).thenReturn(Optional.of(conv));
        Message m = Message.builder().id(7L).conversation(conv).sender(chef).text("yo")
                .createdAt(Instant.parse("2026-02-03T04:05:06Z")).build();
        when(messages.findTop50ByConversationIdOrderByCreatedAtDescIdDesc(10L)).thenReturn(List.of(m));

        List<Map<String, Object>> out = service.history(customer, "10");

        assertThat(out.get(0)).containsOnlyKeys("id", "sender_id", "message", "created_at")
                .containsEntry("id", 7L).containsEntry("sender_id", 2L).containsEntry("message", "yo")
                .containsEntry("created_at", "2026-02-03T04:05:06Z");
    }

    @Test
    void saveMessage_unknownRoom_isSwallowed_andReturnsFalse() {
        when(conversations.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.saveMessage(1L, "99", "x")).isFalse();
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void saveMessage_nonNumericRoomAndNullText_areSwallowed() {
        assertThat(service.saveMessage(1L, "abc", "x")).isFalse();
        when(conversations.findById(10L)).thenReturn(Optional.of(conv));
        when(users.findById(3L)).thenReturn(Optional.of(stranger));
        assertThat(service.saveMessage(3L, "10", null)).isFalse();
    }

    @Test
    void saveMessage_noParticipantCheck_strangerCanPost() {
        when(conversations.findById(10L)).thenReturn(Optional.of(conv));
        when(users.findById(3L)).thenReturn(Optional.of(stranger));

        assertThat(service.saveMessage(3L, "10", 123)).isTrue();

        verify(messages).saveAndFlush(any(Message.class));
    }

    @Test
    void create_targetMustBeAChef_notSelf_andIsGetOrCreate() {
        when(users.findById(3L)).thenReturn(Optional.of(stranger));
        assertThatThrownBy(() -> service.getOrCreateConversation(customer, 3L))
                .isInstanceOfSatisfying(ChatRawException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.getOrCreateConversation(customer, 1L))
                .isInstanceOfSatisfying(ChatRawException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.getOrCreateConversation(customer, "zzz"))
                .isInstanceOfSatisfying(ChatRawException.class, e -> assertThat(e.getStatus()).isEqualTo(400));

        when(users.findById(2L)).thenReturn(Optional.of(chef));
        when(conversations.findByPair(1L, 2L)).thenReturn(Optional.of(conv));
        when(messages.findFirstByConversationIdOrderByCreatedAtDescIdDesc(10L)).thenReturn(Optional.empty());
        assertThat(service.getOrCreateConversation(customer, 2L)).containsEntry("room_id", 10L);
        verify(conversations, never()).save(any());
    }

    @Test
    void create_flagPreservingDjangoBug_disablesTheRoute() {
        assertThat(service.isConversationCreateEnabled()).isTrue();
        ReflectionTestUtils.setField(service, "preserveNoConversationCreateBug", true);
        assertThat(service.isConversationCreateEnabled()).isFalse();
    }
}
