package com.amomeal.marketplace.cart.repository;

import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /** Mirrors half of {@code CartORM.get_cart_by_user}'s {@code get_or_create(owner=user)}. */
    Optional<Cart> findByOwner(CustomUser owner);
}
