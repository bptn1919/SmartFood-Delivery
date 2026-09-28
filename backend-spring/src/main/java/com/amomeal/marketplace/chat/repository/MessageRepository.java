package com.amomeal.marketplace.chat.repository;

import com.amomeal.marketplace.chat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findFirstByConversationIdOrderByCreatedAtDescIdDesc(Long conversationId);

    /** Django: {@code order_by('-created_at')[:50]}. */
    List<Message> findTop50ByConversationIdOrderByCreatedAtDescIdDesc(Long conversationId);
}
