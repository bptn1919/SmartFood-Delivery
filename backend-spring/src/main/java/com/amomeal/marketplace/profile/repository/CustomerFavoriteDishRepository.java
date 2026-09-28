package com.amomeal.marketplace.profile.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.profile.entity.CustomerFavoriteDish;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerFavoriteDishRepository extends JpaRepository<CustomerFavoriteDish, UUID> {

    /** Mirrors CustomerORM.get_favorite_dish (filters deleted=False). */
    Optional<CustomerFavoriteDish> findByUserAndDishUidAndDeletedFalse(CustomUser user, UUID dishUid);

    Optional<CustomerFavoriteDish> findByUserAndDish(CustomUser user, Dish dish);

    /** Mirrors CustomerORM.get_favorite_dishes. */
    List<CustomerFavoriteDish> findAllByUserAndDeletedFalse(CustomUser user);

    /** Backing set for DishUserContext.favoriteDishUids. */
    List<CustomerFavoriteDish> findAllByUserIdAndDeletedFalse(Long userId);
}
