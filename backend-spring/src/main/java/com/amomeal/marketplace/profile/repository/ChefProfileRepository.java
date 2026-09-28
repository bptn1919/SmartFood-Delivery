package com.amomeal.marketplace.profile.repository;

import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Sorting/paging for {@code get_all_chef_profiles}/{@code get_popular_chefs}
 * (Django's {@code sort_by} switch and {@code [:limit]}) is done via the
 * inherited {@code findAll(Pageable)} + a {@code Sort} built in
 * {@link com.amomeal.marketplace.profile.service.ProfileService} — no need
 * for one derived-query method per sort option.
 */
public interface ChefProfileRepository extends JpaRepository<ChefProfile, Long> {

    Optional<ChefProfile> findByUser(CustomUser user);

    Optional<ChefProfile> findByUserId(Long userId);
}
