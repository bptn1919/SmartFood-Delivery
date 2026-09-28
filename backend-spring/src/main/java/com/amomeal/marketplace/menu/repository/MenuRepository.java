package com.amomeal.marketplace.menu.repository;

import com.amomeal.marketplace.menu.entity.Menu;
import com.amomeal.marketplace.menu.entity.MenuStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MenuRepository extends JpaRepository<Menu, UUID> {

    /** Mirrors MenuORM.get_menu_by_uid_include_deleted / the raw `Menu.objects.filter(uid=uid).first()`
     * lookups the service layer uses directly (no deleted filter at all). */
    Optional<Menu> findByUid(UUID uid);

    /**
     * Mirrors the {@code @require_object_permission} decorator's own object
     * lookup, which (unlike the service methods it guards) filters
     * {@code deleted=False} by default (Django's {@code _query_object},
     * {@code check_deleted=True} unless a caller opts out).
     */
    Optional<Menu> findByUidAndDeletedFalse(UUID uid);

    /** Mirrors MenuORM.get_all_menus_of_chef (customer-facing menu list). */
    List<Menu> findAllByChef_IdAndDeletedFalseAndStatusOrderByName(Long chefId, MenuStatus status);

    /** Mirrors MenuORM.get_all_my_menus (chef's own list, any status). */
    List<Menu> findAllByChef_IdAndDeletedFalseOrderByName(Long chefId);
}
