package com.amomeal.marketplace.profile.repository;

import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {

    Optional<CustomerProfile> findByUser(CustomUser user);

    Optional<CustomerProfile> findByUserId(Long userId);
}
