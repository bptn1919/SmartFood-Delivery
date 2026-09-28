package com.amomeal.marketplace.profile.repository;

import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * PORT-NOTE: none of these queries filter {@code deleted} — see
 * {@link CustomerAddress}'s class javadoc for the preserved Django quirk this
 * mirrors (soft-deleting an address never actually hides it from any of these
 * read paths in Django either).
 */
public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    List<CustomerAddress> findAllByUser(CustomUser user);

    Optional<CustomerAddress> findFirstByUserAndSelectedTrue(CustomUser user);

    Optional<CustomerAddress> findFirstByUserOrderByIdDesc(CustomUser user);

    Optional<CustomerAddress> findByUserAndId(CustomUser user, Long id);

    int countByUserAndSelectedTrue(CustomUser user);
}
