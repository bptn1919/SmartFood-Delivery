package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.exception.StockReservationException;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * Ports the inventory-mutating half of ../../backend/dish/orm/dish.py
 * ({@code reduce_quantity} / {@code increase_quantity} / the
 * {@code create_or_update_availability} upsert) — the Postgres source of truth
 * that {@link StockReservationService} permanently deducts on confirm and
 * credits back on a post-confirm cancellation.
 *
 * <p>Both mutations take a {@code SELECT ... FOR UPDATE} row lock, exactly like
 * Django's {@code select_for_update()}, so concurrent confirms/cancels for the
 * same (dish, date) serialize instead of losing an update.
 *
 * <p>Per CLAUDE.md §8b these methods are transactionally self-sufficient: each
 * carries its own {@code @Transactional}, so they are correct whether called
 * standalone or joined into a caller's transaction (which is what
 * {@code confirm()} relies on to keep the status flip and the deduction atomic).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DishInventoryService {

    private final DishAvailabilityRepository availabilityRepository;
    private final StockRedisGateway redisGateway;
    private final PlatformTransactionManager transactionManager;

    /**
     * Django: {@code DishORM.reduce_quantity} — raises rather than creating a
     * row if the dish has no availability record for that day.
     */
    @Transactional
    public DishAvailability reduceQuantity(Dish dish, LocalDate availableDate, int quantity) {
        DishAvailability availability = availabilityRepository.findForUpdate(dish, availableDate)
                .orElseThrow(() -> StockReservationException.noAvailabilityRecord(dish.getName(), availableDate));

        if (availability.getAvailableQuantity() < quantity) {
            throw StockReservationException.insufficientStock(dish.getName(), availableDate);
        }

        availability.setAvailableQuantity(availability.getAvailableQuantity() - quantity);
        return availabilityRepository.save(availability);
    }

    /**
     * Django: {@code DishORM.increase_quantity} — unlike reduce, this one DOES
     * create a missing row (with the refunded quantity), so a cancellation can
     * never fail for lack of a row.
     */
    @Transactional
    public DishAvailability increaseQuantity(Dish dish, LocalDate availableDate, int quantity) {
        DishAvailability availability = availabilityRepository.findForUpdate(dish, availableDate).orElse(null);
        if (availability == null) {
            DishAvailability created = DishAvailability.builder()
                    .dish(dish)
                    .availableDate(availableDate)
                    .availableQuantity(quantity)
                    .build();
            return availabilityRepository.save(created);
        }
        availability.setAvailableQuantity(availability.getAvailableQuantity() + quantity);
        return availabilityRepository.save(availability);
    }

    /**
     * Django: {@code DishORM.create_or_update_availability} — note it forces
     * {@code is_available = True} on the update branch, and only overwrites
     * {@code note} when a non-null one was supplied.
     */
    @Transactional
    public DishAvailability createOrUpdateAvailability(Dish dish, LocalDate availableDate,
                                                       int availableQuantity, String note) {
        DishAvailability availability = availabilityRepository.findByDishAndAvailableDate(dish, availableDate)
                .orElse(null);
        if (availability == null) {
            DishAvailability created = DishAvailability.builder()
                    .dish(dish)
                    .availableDate(availableDate)
                    .availableQuantity(availableQuantity)
                    .available(true)
                    .note(note)
                    .build();
            DishAvailability saved = availabilityRepository.save(created);
            invalidateRedisCounterAfterCommit(dish, availableDate);
            return saved;
        }
        availability.setAvailableQuantity(availableQuantity);
        availability.setAvailable(true);
        if (note != null) {
            availability.setNote(note);
        }
        DishAvailability saved = availabilityRepository.save(availability);
        invalidateRedisCounterAfterCommit(dish, availableDate);
        return saved;
    }

    /**
     * backend-edit 9939179 ({@code transaction.on_commit(_invalidate_redis_counter)}): the Redis
     * counter is {@code available_quantity - SUM(RESERVED)}; the chef just changed the first term
     * behind its back. Drop it once the new value is committed so the next reserve() re-seeds
     * from it. Never lets a cache problem turn a committed chef edit into an API error.
     * Registered on both the create and the update branch, like Django.
     */
    private void invalidateRedisCounterAfterCommit(Dish dish, LocalDate availableDate) {
        java.util.UUID dishUid = dish.getUid();
        Runnable invalidate = () -> {
            try {
                // afterCommit still has the old transaction's resources bound; the gateway's
                // possible post-outage resync reads Postgres, so give it its own transaction.
                TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.setReadOnly(true);
                requiresNew.executeWithoutResult(status -> redisGateway.invalidateCounter(dishUid, availableDate));
            } catch (RuntimeException ex) {
                log.error("Failed to invalidate stock counter for dish {} / {}", dishUid, availableDate, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }
}
