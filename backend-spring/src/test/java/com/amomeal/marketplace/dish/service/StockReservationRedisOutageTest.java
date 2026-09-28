package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.dish.config.StockRedisClient;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.entity.DishCategory;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stock holds with the stock Redis DOWN — the Redis-outage design ported from Django backend-edit
 * commit 9939179 (circuit breaker + Postgres {@code SELECT ... FOR UPDATE} fallback + post-outage
 * counter resync). The stock client points at a DEDICATED Redis container
 * ({@code app.stock.redis-url}, Django's {@code STOCK_REDIS_URL}) that each test really takes
 * down with {@code docker pause} — the TCP connection stays up but nothing answers, so every
 * command hits the 0.3 s timeout, the realistic "hung Redis" outage — and brings back with
 * {@code docker unpause}. Postgres (and the refresh-token Redis) are the shared containers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StockReservationRedisOutageTest {

    private static final GenericContainer<?> STOCK_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);

    static {
        STOCK_REDIS.start();
    }

    @DynamicPropertySource
    static void stockRedis(DynamicPropertyRegistry registry) {
        registry.add("app.stock.redis-url",
                () -> "redis://" + STOCK_REDIS.getHost() + ":" + STOCK_REDIS.getMappedPort(6379) + "/1");
        registry.add("app.stock.redis-socket-timeout-seconds", () -> "0.3");
        registry.add("app.stock.redis-breaker-fail-max", () -> "3");
        registry.add("app.stock.redis-breaker-reset-seconds", () -> "1");
        registry.add("app.stock.sweep-enabled", () -> "false");
    }

    @Autowired StockReservationService service;
    @Autowired StockRedisGateway gateway;
    @Autowired StockRedisClient redis;
    @Autowired DishInventoryService inventoryService;
    @Autowired StockReservationRepository reservationRepository;
    @Autowired DishRepository dishRepository;
    @Autowired DishAvailabilityRepository availabilityRepository;
    @Autowired CheckoutRepository checkoutRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;

    private Dish dish;
    private LocalDate date;
    private String stockKey;
    private Order parentOrder;
    private boolean paused;

    @BeforeEach
    void setUp() {
        date = LocalDate.now().plusDays(1);
        dish = dishRepository.save(Dish.builder()
                .name("Bún chả " + UUID.randomUUID())
                .category(DishCategory.FOOD)
                .price(new BigDecimal("45000.00"))
                .build());
        availabilityRepository.save(DishAvailability.builder()
                .dish(dish).availableDate(date).availableQuantity(10).available(true).build());
        Checkout checkout = checkoutRepository.save(Checkout.builder()
                .fullName("Outage test").phoneNumber("0900000000")
                .deliveryDate(date).deliveryTime(LocalTime.NOON).build());
        parentOrder = orderRepository.save(Order.builder().checkout(checkout).build());
        stockKey = StockRedisClient.stockKey(dish.getUid(), date);
        redis.delete(stockKey);
        gateway.breaker().reset();
    }

    @AfterEach
    void tearDown() {
        redisUp();
        gateway.breaker().reset();
    }

    private void redisDown() {
        DockerClientFactory.instance().client().pauseContainerCmd(STOCK_REDIS.getContainerId()).exec();
        paused = true;
    }

    private void redisUp() {
        if (paused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(STOCK_REDIS.getContainerId()).exec();
            paused = false;
        }
    }

    private long nextItemId() {
        return orderItemRepository.save(OrderItem.builder()
                .order(parentOrder).dish(dish).dishName(dish.getName()).price(dish.getPrice()).quantity(1)
                .build()).getId();
    }

    private int committed() {
        return availabilityRepository.findByDishAndAvailableDate(dish, date)
                .map(DishAvailability::getAvailableQuantity).orElseThrow();
    }

    private long held() {
        return reservationRepository.sumHeldQuantity(dish, date, StockReservationStatus.RESERVED);
    }

    private StockReservationStatus statusOf(long itemId) {
        return reservationRepository.findByOrderItemId(itemId).orElseThrow().getStatus();
    }

    /** Takes Redis down and burns fail-max failures so the breaker is OPEN. */
    private void redisDownAndBreakerOpen() {
        redisDown();
        for (int i = 0; i < 3 && !gateway.breaker().isOpen(); i++) {
            gateway.invalidateCounter(UUID.randomUUID(), date); // each call: one 0.3 s timeout
        }
        assertThat(gateway.breaker().isOpen()).isTrue();
    }

    @Test
    void redisDown_40ThreadContention_exactlyCapacitySucceeds_neverOversells() throws Exception {
        int capacity = 10;
        int contenders = 40;
        List<Long> itemIds = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            itemIds.add(nextItemId());
        }
        redisDown(); // breaker still CLOSED: the first callers pay the timeouts and open it themselves

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        try {
            for (long itemId : itemIds) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        service.reserve(itemId, parentOrder.getUid(), dish, date, 1);
                        succeeded.incrementAndGet();
                    } catch (StockReservationException ex) {
                        rejected.incrementAndGet();
                    } catch (Exception ex) {
                        unexpected.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(gateway.breaker().isOpen()).isTrue();
        assertThat(unexpected.get()).isZero();
        assertThat(succeeded.get()).isEqualTo(capacity);
        assertThat(rejected.get()).isEqualTo(contenders - capacity);
        long rows = itemIds.stream().map(reservationRepository::findByOrderItemId)
                .filter(java.util.Optional::isPresent).count();
        assertThat(rows).isEqualTo(capacity);
        assertThat(held()).isEqualTo(capacity);
        assertThat(committed()).isEqualTo(10); // holds are not deductions

        // Redis back: the first reserve after the cooldown resyncs and re-seeds from the ledger
        // (10 committed - 10 held = 0), so Redis cannot oversell what Postgres handed out.
        redisUp();
        Thread.sleep(1100);
        assertThatThrownBy(() -> service.reserve(nextItemId(), parentOrder.getUid(), dish, date, 1))
                .isInstanceOf(StockReservationException.class);
        assertThat(gateway.breaker().isOpen()).isFalse();
        assertThat(redis.get(stockKey)).isZero();
    }

    @Test
    void redisDown_reserveConfirmRelease_allWorkThroughPostgres() {
        redisDownAndBreakerOpen();

        long a = nextItemId();
        long b = nextItemId();
        service.reserve(a, parentOrder.getUid(), dish, date, 3);
        service.reserve(b, parentOrder.getUid(), dish, date, 2);
        assertThat(statusOf(a)).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(held()).isEqualTo(5);

        // Not enough left for 6 (10 - 5 held) — the fallback enforces capacity on its own.
        assertThatThrownBy(() -> service.reserve(nextItemId(), parentOrder.getUid(), dish, date, 6))
                .isInstanceOf(StockReservationException.class);

        assertThat(service.confirm(a, dish, date, 3)).isTrue(); // Postgres-only, as always
        assertThat(committed()).isEqualTo(7);
        assertThat(statusOf(a)).isEqualTo(StockReservationStatus.CONFIRMED);

        service.release(b, dish, date, 2, false);               // cancel before payment
        assertThat(statusOf(b)).isEqualTo(StockReservationStatus.RELEASED);
        service.release(a, dish, date, 3, false);               // cancel after payment
        assertThat(statusOf(a)).isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(committed()).isEqualTo(10);
        assertThat(held()).isZero();

        // A missing availability row is still a real error, not an "outage".
        assertThatThrownBy(() -> service.reserve(nextItemId(), parentOrder.getUid(), dish, date.plusDays(9), 1))
                .isInstanceOf(StockReservationException.class);
    }

    @Test
    void recovery_dropsStaleCounters_andReseedsThemFromTheLedger() throws Exception {
        // Healthy: seed 10, hold 2 through Redis.
        long first = nextItemId();
        service.reserve(first, parentOrder.getUid(), dish, date, 2);
        assertThat(redis.get(stockKey)).isEqualTo(8L);

        // Outage: 3 more units held through Postgres, and a chef raises the day's quantity to 20
        // (its counter invalidation cannot reach Redis). The surviving counter (8) is now stale.
        redisDownAndBreakerOpen();
        long second = nextItemId();
        service.reserve(second, parentOrder.getUid(), dish, date, 3);
        inventoryService.createOrUpdateAvailability(dish, date, 20, null);
        assertThat(held()).isEqualTo(5);

        // Redis returns; after the cooldown the next reserve probes it, drops every counter
        // touched since the outage began, and re-seeds from Postgres: 20 committed - 5 held.
        redisUp();
        Thread.sleep(1100);
        long third = nextItemId();
        service.reserve(third, parentOrder.getUid(), dish, date, 1);

        assertThat(gateway.breaker().isOpen()).isFalse();
        assertThat(statusOf(third)).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(held()).isEqualTo(6);
        assertThat(redis.get(stockKey)).isEqualTo(committed() - held()); // 20 - 6 = 14
        assertThat(redis.get(stockKey)).isEqualTo(14L);

        // ...and the counter keeps tracking the ledger afterwards.
        service.release(second, dish, date, 3, false);
        assertThat(redis.get(stockKey)).isEqualTo(committed() - held()); // 17
    }

    @Test
    void credit_neverResurrectsAMissingCounter() {
        long item = nextItemId();
        service.reserve(item, parentOrder.getUid(), dish, date, 4);
        redis.delete(stockKey); // e.g. Redis restarted empty
        service.release(item, dish, date, 4, false);
        // _CREDIT_SCRIPT: a bare INCRBY would have created the key with just 4 and it would stick.
        assertThat(redis.get(stockKey)).isNull();
        service.reserve(nextItemId(), parentOrder.getUid(), dish, date, 1);
        assertThat(redis.get(stockKey)).isEqualTo(9L); // lazily re-seeded from Postgres
    }
}
