package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OutboxEvent;
import com.amomeal.marketplace.order.entity.OutboxEventStatus;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.repository.NotificationIdempotencyKeyRepository;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.order.repository.OutboxEventRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The transactional outbox (Django backend-edit 9939179, {@code order/outbox.py} +
 * {@code dispatch_pending_outbox_events}) end to end: real Postgres, real {@code @Async} +
 * {@code @Retryable} notification task, real idempotency-key table. The email transport is mocked
 * (to count sends / fail on demand) and the task publisher is spied (to simulate the async
 * hand-off failing — Django's "broker down").
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "app.stock.sweep-enabled=false",
        "app.order.notification.retry-delay-ms=20",
        "app.order.outbox.max-attempts=3",
        "app.order.outbox.dispatch-enabled=false"
})
class OutboxServiceTest {

    @Autowired OutboxService outboxService;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired NotificationIdempotencyKeyRepository keyRepository;
    @Autowired CustomUserRepository userRepository;
    @Autowired CheckoutRepository checkoutRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean OrderEmailService emailService;
    @MockitoSpyBean OutboxTaskPublisher publisher;

    private UUID orderUid;
    private String key;

    @BeforeEach
    void setUp() {
        String nonce = UUID.randomUUID().toString();
        CustomUser owner = userRepository.save(CustomUser.builder()
                .username("outbox-" + nonce).email("outbox-" + nonce + "@x.test").password("x").build());
        Checkout checkout = checkoutRepository.save(Checkout.builder().owner(owner).fullName("O").phoneNumber("09")
                .deliveryDate(LocalDate.now()).deliveryTime(LocalTime.NOON).build());
        orderUid = orderRepository.save(Order.builder().checkout(checkout).owner(owner).build()).getUid();
        key = "order-confirmed:" + orderUid;
    }

    private Map<String, Object> ourPayload() {
        return argThat(p -> p != null && key.equals(p.get("idempotency_key")));
    }

    private OutboxEvent event() {
        return outboxRepository.findByIdempotencyKey(key).orElseThrow();
    }

    private OutboxEvent awaitEvent(Predicate<OutboxEvent> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        OutboxEvent e = event();
        while (!condition.test(e) && System.nanoTime() < deadline) {
            Thread.sleep(25);
            e = event();
        }
        return e;
    }

    private void makeDue() {
        jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE idempotency_key = ?", key);
    }

    private int emailsForOrder() {
        return (int) mockingDetails(emailService).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("sendOrderStatusEmail"))
                .filter(inv -> inv.getArgument(1) instanceof Order o && orderUid.equals(o.getUid()))
                .count();
    }

    @Test
    void backoff_isDjangosFormula() {
        assertThat(outboxService.backoff(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(outboxService.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(outboxService.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(outboxService.backoff(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(outboxService.backoff(7)).isEqualTo(Duration.ofSeconds(300)); // 320 capped
        assertThat(outboxService.backoff(40)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void aRolledBackTransaction_leavesNoEvent_andNeverPublishes() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxService.enqueueOrderNotification(key, orderUid, "CONFIRMED");
            assertThat(outboxRepository.findByIdempotencyKey(key)).isPresent(); // visible inside the tx
            status.setRollbackOnly();
        });

        Thread.sleep(300);
        assertThat(outboxRepository.findByIdempotencyKey(key)).isEmpty();
        verify(publisher, never()).publish(any(), ourPayload());
        assertThat(emailsForOrder()).isZero();
    }

    @Test
    void publishesOnlyAfterCommit_thenMarksSent_andSendsOnce() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxService.enqueueOrderNotification(key, orderUid, "CONFIRMED");
            // Same transaction as the business change: nothing handed off before commit.
            verify(publisher, never()).publish(any(), ourPayload());
        });

        OutboxEvent sent = awaitEvent(e -> e.getStatus() == OutboxEventStatus.SENT);
        assertThat(sent.getStatus()).isEqualTo(OutboxEventStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(1);
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getTaskName()).isEqualTo(OutboxTaskPublisher.NOTIFICATION_TASK);
        assertThat(sent.getPayload()).containsEntry("event_type", "CONFIRMED")
                .containsEntry("order_uid", orderUid.toString());
        verify(publisher, times(1)).publish(eq(OutboxTaskPublisher.NOTIFICATION_TASK), ourPayload());
        assertThat(emailsForOrder()).isEqualTo(1);
        assertThat(keyRepository.existsByKey(key)).isTrue();
    }

    @Test
    void handOffFailure_keepsTheEventPending_withBackoff_andTheDispatcherDeliversItLater() throws Exception {
        doThrow(new TaskRejectedException("executor unavailable"))
                .doCallRealMethod()
                .when(publisher).publish(eq(OutboxTaskPublisher.NOTIFICATION_TASK), ourPayload());

        Instant before = Instant.now();
        outboxService.enqueueOrderNotification(key, orderUid, "CONFIRMED"); // own transaction

        OutboxEvent failed = event();
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).startsWith("TaskRejectedException: executor unavailable");
        assertThat(failed.getNextAttemptAt()).isAfter(before.plusSeconds(4)); // backoff(1) = 5 s
        assertThat(emailsForOrder()).isZero();

        // Not due yet: the dispatcher leaves it alone.
        outboxService.dispatchPending();
        assertThat(event().getAttempts()).isEqualTo(1);
        verify(publisher, times(1)).publish(any(), ourPayload());

        // Due: the dispatcher publishes it; the task runs; the event is SENT.
        makeDue();
        assertThat(outboxService.dispatchPending()).isGreaterThanOrEqualTo(1);
        OutboxEvent sent = awaitEvent(e -> e.getStatus() == OutboxEventStatus.SENT);
        assertThat(sent.getStatus()).isEqualTo(OutboxEventStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(emailsForOrder()).isEqualTo(1);
    }

    @Test
    void aTaskThatKeepsFailing_isRetriedWithBackoff_thenGivenUpAsFailed() throws Exception {
        doThrow(new IllegalStateException("smtp down")).when(emailService)
                .sendOrderStatusEmail(any(), argThat(o -> o != null && orderUid.equals(o.getUid())), any());

        outboxService.enqueueOrderNotification(key, orderUid, "CONFIRMED");
        // The notification task retries 4x itself, then reports "failed" -> attempt 1 recorded.
        OutboxEvent first = awaitEvent(e -> e.getAttempts() == 1);
        assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(first.getLastError()).isNotBlank();

        makeDue();
        outboxService.dispatchPending();
        assertThat(awaitEvent(e -> e.getAttempts() == 2).getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        makeDue();
        outboxService.dispatchPending();
        OutboxEvent gaveUp = awaitEvent(e -> e.getStatus() == OutboxEventStatus.FAILED);
        assertThat(gaveUp.getStatus()).isEqualTo(OutboxEventStatus.FAILED); // max-attempts = 3
        assertThat(gaveUp.getAttempts()).isEqualTo(3);

        // A FAILED event is never picked up again, and no claim was left behind.
        makeDue();
        outboxService.dispatchPending();
        Thread.sleep(200);
        verify(publisher, times(3)).publish(any(), ourPayload());
        assertThat(keyRepository.existsByKey(key)).isFalse();
        assertThat(emailsForOrder()).isEqualTo(12); // 3 outbox attempts x 4 task attempts, none delivered
    }

    @Test
    void redeliveryAndARacingSecondPublish_stillSendExactlyOnce() throws Exception {
        outboxService.enqueueOrderNotification(key, orderUid, "CONFIRMED");
        assertThat(awaitEvent(e -> e.getStatus() == OutboxEventStatus.SENT).getStatus())
                .isEqualTo(OutboxEventStatus.SENT);

        // Webhook redelivery enqueues the same key again: the first row is kept, nothing republished.
        outboxService.enqueueOrderNotification(key, orderUid, "CONFIRMED");
        assertThat(outboxRepository.findAll().stream().filter(e -> key.equals(e.getIdempotencyKey())).count())
                .isEqualTo(1);

        // Worst case of at-least-once delivery: the event is published a second time (inline
        // dispatch racing the sweeper). The idempotency key makes the second run a no-op.
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> outboxService.publishEvent(event()));
        Thread.sleep(500);

        verify(publisher, times(2)).publish(any(), ourPayload());
        assertThat(emailsForOrder()).isEqualTo(1);
        assertThat(event().getStatus()).isEqualTo(OutboxEventStatus.SENT);
    }
}
