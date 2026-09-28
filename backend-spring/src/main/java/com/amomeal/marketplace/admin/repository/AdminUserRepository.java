package com.amomeal.marketplace.admin.repository;

import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Admin-owned read view over {@link CustomUser} (specification queries for the user list and the
 * new-user count); the users module's own repository is not edited.
 */
public interface AdminUserRepository extends JpaRepository<CustomUser, Long>, JpaSpecificationExecutor<CustomUser> {
}
