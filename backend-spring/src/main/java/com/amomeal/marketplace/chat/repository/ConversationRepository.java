package com.amomeal.marketplace.chat.repository;

import com.amomeal.marketplace.chat.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** Django: {@code filter(Q(customer=user) | Q(chef=user)).order_by('-updated_at')}. */
    @Query("select c from Conversation c join fetch c.customer join fetch c.chef "
            + "where c.customer.id = :userId or c.chef.id = :userId order by c.updatedAt desc, c.id desc")
    List<Conversation> findAllOfUser(Long userId);

    @Query("select c from Conversation c join fetch c.customer join fetch c.chef where c.id = :id")
    Optional<Conversation> findWithUsersById(Long id);

    @Query("select c from Conversation c join fetch c.customer join fetch c.chef "
            + "where c.customer.id = :customerId and c.chef.id = :chefId")
    Optional<Conversation> findByPair(Long customerId, Long chefId);
}
