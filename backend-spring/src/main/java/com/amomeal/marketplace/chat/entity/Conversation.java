package com.amomeal.marketplace.chat.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Mirrors ../../backend/chat/models.py::Conversation (unique customer+chef pair). */
@Entity
@Table(name = "chat_conversation", uniqueConstraints =
        @UniqueConstraint(name = "uk_chat_conversation_customer_chef", columnNames = {"customer_id", "chef_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomUser customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chef_id", nullable = false)
    private CustomUser chef;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Django {@code auto_now}: bumped on every {@code save()} of the conversation (NOT on new messages). */
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
