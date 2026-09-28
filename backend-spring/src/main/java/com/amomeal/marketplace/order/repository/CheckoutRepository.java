package com.amomeal.marketplace.order.repository;

import com.amomeal.marketplace.order.entity.Checkout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Mirrors the {@code Checkout.objects.*} half of ../../backend/order/orm/order.py::OrderORM. */
public interface CheckoutRepository extends JpaRepository<Checkout, UUID> {

    Optional<Checkout> findByUid(UUID uid);
}
