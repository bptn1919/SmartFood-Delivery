package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.NotificationIdempotencyKeyRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The order notification task end to end — real {@code @Async} thread, real
 * {@code @Retryable} loop, real Postgres UNIQUE constraint — proving the one
 * property the Django design exists for: <b>a retry or a redelivery never
 * double-sends</b>. Only the email transport is mocked (to fail on demand and to
 * count sends).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "app.stock.sweep-enabled=false",
        "app.order.notification.retry-delay-ms=50"
})
class OrderNotificationIdempotencyTest {

    @Autowired OrderNotificationService notificationService;
    @Autowired NotificationClaimService claimService;
    @Autowired NotificationIdempotencyKeyRepository keyRepository;
    @Autowired CustomUserRepository userRepository;
    @Autowired CheckoutRepository checkoutRepository;
    @Autowired OrderRepository orderRepository;

    @MockitoBean OrderEmailService emailService;

    private UUID orderUid;

    @BeforeEach
    void setUp() {
        String nonce = UUID.randomUUID().toString();
        CustomUser owner = userRepository.save(CustomUser.builder()
                .username("notif-" + nonce).email("notif-" + nonce + "@x.test").password("x").build());
        Checkout checkout = checkoutRepository.save(Checkout.builder().owner(owner).fullName("N").phoneNumber("09")
                .deliveryDate(LocalDate.now()).deliveryTime(LocalTime.NOON).build());
        orderUid = orderRepository.save(Order.builder().checkout(checkout).owner(owner).build()).getUid();
    }

    @Test
    void transientFailure_isRetried_sendsExactlyOnce_andARedeliveryAfterSuccessIsANoOp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
            return null;
        }).when(emailService).sendOrderStatusEmail(any(), any(), any());
        String key = "order-confirmed:" + orderUid;

        String result = notificationService.sendOrderNotificationTask(key, orderUid, "CONFIRMED").get(10, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("sent");
        assertThat(calls.get()).isEqualTo(2); // failed once, then the retry re-claimed and sent
        assertThat(keyRepository.existsByKey(key)).isTrue();

        // At-least-once redelivery of the SAME task after it succeeded:
        String redelivered = notificationService.sendOrderNotificationTask(key, orderUid, "CONFIRMED").get(10, TimeUnit.SECONDS);
        assertThat(redelivered).isEqualTo("skipped_duplicate");
        assertThat(calls.get()).isEqualTo(2);
        verify(emailService, times(2)).sendOrderStatusEmail(any(), any(), any());
    }

    @Test
    void permanentFailure_exhausts4Attempts_andLeavesTheKeyUnclaimed() throws Exception {
        doThrow(new IllegalStateException("down")).when(emailService).sendOrderStatusEmail(any(), any(), any());
        String key = "order-expired:" + orderUid;

        String result = notificationService.sendOrderNotificationTask(key, orderUid, "CANCELLED_EXPIRED").get(10, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("failed");
        verify(emailService, times(4)).sendOrderStatusEmail(any(), any(), any());
        // Released, so a legitimate future redelivery can still try (Django: claim.delete()).
        assertThat(keyRepository.existsByKey(key)).isFalse();
    }

    @Test
    void concurrentClaims_ofTheSameKey_exactlyOneWins() throws Exception {
        String key = "race:" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> claimService.claim(key, OrderNotificationService.TASK_NAME));
            }
            int winners = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(keyRepository.findByKey(key)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDeliveries_ofTheSameTask_sendOnce() throws Exception {
        String key = "dup:" + orderUid;
        List<java.util.concurrent.CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(notificationService.sendOrderNotificationTask(key, orderUid, "CONFIRMED"));
        }
        long sent = 0;
        for (var f : futures) {
            if ("sent".equals(f.get(10, TimeUnit.SECONDS))) {
                sent++;
            }
        }
        assertThat(sent).isEqualTo(1);
        verify(emailService, times(1)).sendOrderStatusEmail(any(), any(), any());
    }
}
