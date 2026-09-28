package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.dish.config.StockRedisClient;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.dish.exception.StockReservationException;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.dish.repository.StockReservationRepository;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack tests for the stock hold mechanism ported from
 * ../../backend/dish/services/stock_reservation.py, against a <b>real Redis</b>
 * and a <b>real Postgres</b> (both via the shared
 * {@link TestcontainersConfiguration}) — nothing is mocked, because the whole
 * point of this module is the interaction between an atomic Redis counter and a
 * durable Postgres ledger, and a mocked Redis would prove nothing about
 * oversell safety.
 *
 * <p>The {@code @Scheduled} sweep is disabled here so it cannot race the
 * assertions; {@link StockHoldSweepScheduler#sweepOnce()} is driven explicitly
 * instead.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "app.stock.sweep-enabled=false")
class StockReservationServiceTest {

    @Autowired
    private StockReservationService service;
    @Autowired
    private StockReservationRepository reservationRepository;
    @Autowired
    private DishRepository dishRepository;
    @Autowired
    private DishAvailabilityRepository availabilityRepository;
    @Autowired
    private StockRedisClient redis;

    private Dish dish;
    private LocalDate date;
    private String stockKey;

    @Autowired
    private CheckoutRepository checkoutRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;

    /** Parent order for the ledger rows' order_item FK (added by the `order` port, V10). */
    private Order parentOrder;

    /**
     * stock_reservation.order_item_id is a real FK to order_item since the `order` port
     * (V10__init_order.sql), so each "item id" must now be a real order_item row. Only the
     * FK target changed — every assertion below is about the ledger/counter, untouched.
     */
    private long nextItemId() {
        return orderItemRepository.save(OrderItem.builder()
                .order(parentOrder)
                .dish(dish)
                .dishName(dish.getName())
                .price(dish.getPrice())
                .quantity(1)
                .build()).getId();
    }

    @BeforeEach
    void setUp() {
        date = LocalDate.now().plusDays(1);
        dish = dishRepository.save(Dish.builder()
                .name("Phở bò " + UUID.randomUUID())
                .category(DishCategory.FOOD)
                .price(new BigDecimal("55000.00"))
                .build());
        availabilityRepository.save(DishAvailability.builder()
                .dish(dish)
                .availableDate(date)
                .availableQuantity(10)
                .available(true)
                .build());
        Checkout checkout = checkoutRepository.save(Checkout.builder()
                .fullName("Stock test").phoneNumber("0900000000")
                .deliveryDate(date).deliveryTime(LocalTime.NOON).build());
        parentOrder = orderRepository.save(Order.builder().checkout(checkout).build());
        stockKey = StockRedisClient.stockKey(dish.getUid(), date);
        // Each test gets a pristine counter — the lazy seed must be exercised.
        redis.delete(stockKey);
    }

    private int committedQuantity() {
        return availabilityRepository.findByDishAndAvailableDate(dish, date)
                .map(DishAvailability::getAvailableQuantity)
                .orElseThrow();
    }

    // =====================================================================
    // reserve
    // =====================================================================

    @Test
    void reserve_lazilySeedsRedisFromPostgres_thenDecrements_andWritesTheLedgerRow() {
        assertThat(redis.get(stockKey)).isNull();

        long itemId = nextItemId();
        UUID orderUid = UUID.randomUUID();
        service.reserve(itemId, orderUid, dish, date, 3);

        // 10 committed - 0 held = seed 10, minus the 3 just taken
        assertThat(redis.get(stockKey)).isEqualTo(7L);

        StockReservation row = reservationRepository.findByOrderItemId(itemId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(row.getQuantity()).isEqualTo(3);
        assertThat(row.getOrderUid()).isEqualTo(orderUid);
        assertThat(row.getExpiresAt()).isAfter(Instant.now());
        // A hold does NOT touch the permanent inventory — only confirm() does.
        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    void reserve_seedSubtractsExistingActiveHolds_afterARedisWipe() {
        long firstItem = nextItemId();
        service.reserve(firstItem, UUID.randomUUID(), dish, date, 4);
        assertThat(redis.get(stockKey)).isEqualTo(6L);

        // Simulate Redis losing its state entirely.
        redis.delete(stockKey);

        long secondItem = nextItemId();
        service.reserve(secondItem, UUID.randomUUID(), dish, date, 1);
        // Reseeded from Postgres as 10 - 4 (still RESERVED) = 6, then -1.
        assertThat(redis.get(stockKey)).isEqualTo(5L);
    }

    @Test
    void reserve_withoutAnAvailabilityRow_throwsAndWritesNothing() {
        LocalDate noStockDate = date.plusDays(30);
        long itemId = nextItemId();

        assertThatThrownBy(() -> service.reserve(itemId, UUID.randomUUID(), dish, noStockDate, 1))
                .isInstanceOf(StockReservationException.class)
                .hasMessageContaining("chưa có sẵn trong ngày");

        assertThat(reservationRepository.findByOrderItemId(itemId)).isEmpty();
        assertThat(redis.get(StockRedisClient.stockKey(dish.getUid(), noStockDate))).isNull();
    }

    @Test
    void reserve_beyondAvailableStock_throws_andLeavesTheCounterIntact() {
        long itemId = nextItemId();

        assertThatThrownBy(() -> service.reserve(itemId, UUID.randomUUID(), dish, date, 11))
                .isInstanceOf(StockReservationException.class)
                .hasMessageContaining("Không đủ số lượng món");

        // The Lua script seeded the key but refused to decrement.
        assertThat(redis.get(stockKey)).isEqualTo(10L);
        assertThat(reservationRepository.findByOrderItemId(itemId)).isEmpty();
    }

    @Test
    void reserve_reopensATerminalHold_butRefusesAStillActiveOne() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 2);

        // Still RESERVED -> refuse loudly rather than silently overwriting a live hold.
        assertThatThrownBy(() -> service.reserve(itemId, UUID.randomUUID(), dish, date, 2))
                .isInstanceOf(StockReservationException.class)
                .hasMessageContaining("refusing to reopen a still-active hold");
        // ...and the compensating incrby put the speculatively-taken units back.
        assertThat(redis.get(stockKey)).isEqualTo(8L);

        // Let it expire away, then the late-webhook recovery path may reopen it.
        service.release(itemId, dish, date, 2, true);
        assertThat(reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.EXPIRED);
        assertThat(redis.get(stockKey)).isEqualTo(10L);

        service.reserve(itemId, UUID.randomUUID(), dish, date, 2);
        assertThat(reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.RESERVED);
        assertThat(redis.get(stockKey)).isEqualTo(8L);
    }

    // =====================================================================
    // Concurrency — the reason the counter lives in Redis at all
    // =====================================================================

    @Test
    void reserve_isAtomicUnderConcurrency_neverOversells() throws Exception {
        int capacity = 10; // seeded in setUp
        int contenders = 40;

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Long> itemIds = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            itemIds.add(nextItemId());
        }

        try {
            for (long itemId : itemIds) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        service.reserve(itemId, UUID.randomUUID(), dish, date, 1);
                        succeeded.incrementAndGet();
                    } catch (StockReservationException ex) {
                        rejected.incrementAndGet();
                    } catch (Exception ex) {
                        // Any other failure is a real bug — surface it as neither bucket,
                        // which makes the assertions below fail loudly.
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(capacity);
        assertThat(rejected.get()).isEqualTo(contenders - capacity);
        assertThat(redis.get(stockKey)).isZero();

        long reservedRows = itemIds.stream()
                .map(reservationRepository::findByOrderItemId)
                .filter(java.util.Optional::isPresent)
                .count();
        assertThat(reservedRows).isEqualTo(capacity);
        assertThat(reservationRepository.sumHeldQuantity(dish, date, StockReservationStatus.RESERVED))
                .isEqualTo(capacity);
    }

    // =====================================================================
    // confirm
    // =====================================================================

    @Test
    void confirm_deductsPostgresOnce_andIsIdempotentOnRetry() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 4);

        assertThat(service.confirm(itemId, dish, date, 4)).isTrue();
        assertThat(reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(committedQuantity()).isEqualTo(6);
        // Redis is deliberately untouched by confirm — the sale is final and the
        // counter was already decremented at reserve() time.
        assertThat(redis.get(stockKey)).isEqualTo(6L);

        // A webhook retry must not deduct a second time.
        assertThat(service.confirm(itemId, dish, date, 4)).isFalse();
        assertThat(committedQuantity()).isEqualTo(6);
    }

    @Test
    void confirm_returnsFalseWhenTheHoldAlreadyExpired_soCallersCanRecover() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 2);
        service.release(itemId, dish, date, 2, true); // TTL sweep got there first

        // False here means "nothing was ever deducted" — the distinction
        // payment/_sync_successful_payment relies on.
        assertThat(service.confirm(itemId, dish, date, 2)).isFalse();
        assertThat(committedQuantity()).isEqualTo(10);
    }

    // =====================================================================
    // release
    // =====================================================================

    @Test
    void release_beforeConfirm_creditsRedisBack_andIsIdempotent() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 3);
        assertThat(redis.get(stockKey)).isEqualTo(7L);

        service.release(itemId, dish, date, 3, false);
        assertThat(reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.RELEASED);
        assertThat(redis.get(stockKey)).isEqualTo(10L);
        assertThat(committedQuantity()).isEqualTo(10); // never deducted, never credited

        // Second call finds nothing RESERVED and nothing CONFIRMED -> pure no-op.
        service.release(itemId, dish, date, 3, false);
        assertThat(redis.get(stockKey)).isEqualTo(10L);
        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    void release_afterConfirm_cancelsTheSale_andRestoresPostgresExactlyOnce() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 4);
        service.confirm(itemId, dish, date, 4);
        assertThat(committedQuantity()).isEqualTo(6);

        // Explicit cancel of an already-paid order.
        service.release(itemId, dish, date, 4, false);
        assertThat(reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(committedQuantity()).isEqualTo(10);
        // backend-edit 9939179: the Redis counter (decremented at reserve, left so by confirm) is
        // credited back too, or it would keep "locking" stock Postgres already returned.
        assertThat(redis.get(stockKey)).isEqualTo(10L);

        // Idempotent: a second cancel must not double-credit the inventory (nor the counter).
        service.release(itemId, dish, date, 4, false);
        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(redis.get(stockKey)).isEqualTo(10L);
    }

    @Test
    void release_expired_mustNotCancelASaleThatConfirmedFirst() {
        // This is the race documented in ORDER_INVENTORY_FLOW.md section 5.5: the
        // sweep selected the row while it was RESERVED, confirm() won, and the sweep
        // must back off silently instead of refunding a real sale.
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 5);
        service.confirm(itemId, dish, date, 5);
        assertThat(committedQuantity()).isEqualTo(5);

        service.release(itemId, dish, date, 5, true);

        assertThat(reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(committedQuantity()).isEqualTo(5);  // NOT restored
        assertThat(redis.get(stockKey)).isEqualTo(5L); // NOT credited back
    }

    // =====================================================================
    // Expiry sweep (the @Scheduled / Celery Beat job)
    // =====================================================================

    @Test
    void releaseExpiredHolds_sweepsOnlyPastDueReservedRows_andReturnsTheirOrderUids() {
        long expiredItem = nextItemId();
        long liveItem = nextItemId();
        UUID expiredOrder = UUID.randomUUID();

        // 0-second TTL -> already expired by the time the sweep runs.
        service.reserve(expiredItem, expiredOrder, dish, date, 2, 0L);
        service.reserve(liveItem, UUID.randomUUID(), dish, date, 1, 3600L);
        assertThat(redis.get(stockKey)).isEqualTo(7L);

        Set<UUID> touched = service.releaseExpiredHolds();

        assertThat(touched).containsExactly(expiredOrder);
        assertThat(reservationRepository.findByOrderItemId(expiredItem).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.EXPIRED);
        assertThat(reservationRepository.findByOrderItemId(liveItem).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.RESERVED);
        // Only the expired hold's 2 units come back.
        assertThat(redis.get(stockKey)).isEqualTo(9L);
        // The permanent inventory was never touched by either hold.
        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    void releaseExpiredHolds_isSafeToRunTwice() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 2, 0L);

        service.releaseExpiredHolds();
        assertThat(redis.get(stockKey)).isEqualTo(10L);

        // Second tick finds nothing left to expire.
        assertThat(service.releaseExpiredHolds()).isEmpty();
        assertThat(redis.get(stockKey)).isEqualTo(10L);
    }

    @Test
    void expiresAt_honoursTheConfiguredTtl() {
        long itemId = nextItemId();
        Instant before = Instant.now();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 1, 120L);

        Instant expiresAt = reservationRepository.findByOrderItemId(itemId).orElseThrow().getExpiresAt();
        assertThat(expiresAt).isBetween(before.plus(119, ChronoUnit.SECONDS),
                Instant.now().plus(121, ChronoUnit.SECONDS));
    }

    // =====================================================================
    // Operator recovery tool
    // =====================================================================

    @Test
    void rebuildRedisCounters_reDerivesEveryActiveCounterFromPostgres() {
        long itemId = nextItemId();
        service.reserve(itemId, UUID.randomUUID(), dish, date, 6);
        assertThat(redis.get(stockKey)).isEqualTo(4L);

        // Redis incident: the counter is wiped AND re-seeded with a wrong value.
        redis.set(stockKey, 999);

        int rebuilt = service.rebuildRedisCounters();

        assertThat(rebuilt).isGreaterThanOrEqualTo(1);
        // 10 committed - 6 still RESERVED = 4
        assertThat(redis.get(stockKey)).isEqualTo(4L);
    }
}
