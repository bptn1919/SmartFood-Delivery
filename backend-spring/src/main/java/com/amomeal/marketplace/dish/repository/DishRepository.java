package com.amomeal.marketplace.dish.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DishRepository extends JpaRepository<Dish, UUID>, JpaSpecificationExecutor<Dish> {

    /** Mirrors DishORM.get_dish_by_uid (always filters deleted=False). */
    Optional<Dish> findByUidAndDeletedFalse(UUID uid);

    /** Mirrors DishORM.get_dish_by_uid_including_deleted (restore path). */
    Optional<Dish> findByUid(UUID uid);

    /** Mirrors DishORM.get_dish_by_uids. */
    List<Dish> findAllByUidInAndDeletedFalse(List<UUID> uids);

    List<Dish> findAllByDeletedFalse();

    /** Mirrors DishSearchService.retrieve_candidates stage 1 (exact name_no_accent, case-insensitive). */
    List<Dish> findAllByNameNoAccentIgnoreCaseAndDeletedFalse(String nameNoAccent);

    /** Eager-loads what the top-dish ranking and search result formatting need. */
    @Query("SELECT d FROM Dish d LEFT JOIN FETCH d.attachment LEFT JOIN FETCH d.owner "
            + "LEFT JOIN FETCH d.location WHERE d.deleted = false")
    List<Dish> findAllLiveWithRelations();
}
