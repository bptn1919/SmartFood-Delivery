package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The claim/send/release protocol of ../../backend/order/tasks.py::send_order_notification_task,
 * one execution at a time. {@code OrderNotificationIdempotencyTest} runs the same thing
 * against real Postgres with the real async/retry wrapping.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotificationServiceTest {

    @Mock NotificationClaimService claimService;
    @Mock OrderRepository orderRepository;
    @Mock OrderEmailService emailService;
    @InjectMocks OrderNotificationService service;

    private final UUID orderUid = UUID.randomUUID();

    private Order orderWithOwner(String email) {
        return Order.builder().uid(orderUid).owner(CustomUser.builder().id(1L).email(email).build()).build();
    }

    @Test
    void duplicateKey_isSkipped_withNoSideEffect() {
        when(claimService.claim("k", OrderNotificationService.TASK_NAME)).thenReturn(false);
        assertThat(service.runOnce("k", orderUid, "CONFIRMED")).isEqualTo("skipped_duplicate");
        verifyNoInteractions(orderRepository, emailService);
        verify(claimService, never()).release(any());
    }

    @Test
    void freshKey_sends_andKeepsTheClaim() {
        when(claimService.claim(any(), any())).thenReturn(true);
        Order order = orderWithOwner("c@x.test");
        when(orderRepository.findDetailedByUid(orderUid)).thenReturn(Optional.of(order));

        assertThat(service.runOnce("k", orderUid, "CANCELLED_EXPIRED")).isEqualTo("sent");

        InOrder inOrder = inOrder(claimService, emailService);
        inOrder.verify(claimService).claim("k", OrderNotificationService.TASK_NAME);
        inOrder.verify(emailService).sendOrderStatusEmail(order.getOwner(), order, "CANCELLED_EXPIRED");
        verify(claimService, never()).release(any());
    }

    @Test
    void noRecipient_returnsNormally_andKeepsTheClaim() {
        when(claimService.claim(any(), any())).thenReturn(true);
        when(orderRepository.findDetailedByUid(orderUid)).thenReturn(Optional.empty());
        assertThat(service.runOnce("k", orderUid, "CONFIRMED")).isEqualTo("no_recipient");

        when(orderRepository.findDetailedByUid(orderUid)).thenReturn(Optional.of(orderWithOwner("")));
        assertThat(service.runOnce("k2", orderUid, "CONFIRMED")).isEqualTo("no_recipient");
        verify(claimService, never()).release(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void failure_releasesTheClaimBeforeRethrowing_soTheRetryCanReclaim() {
        when(claimService.claim(any(), any())).thenReturn(true);
        Order order = orderWithOwner("c@x.test");
        when(orderRepository.findDetailedByUid(orderUid)).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("template boom")).when(emailService).sendOrderStatusEmail(any(), any(), any());

        assertThatThrownBy(() -> service.runOnce("k", orderUid, "CONFIRMED")).hasMessage("template boom");

        InOrder inOrder = inOrder(claimService, emailService);
        inOrder.verify(claimService).claim("k", OrderNotificationService.TASK_NAME);
        inOrder.verify(emailService).sendOrderStatusEmail(any(), any(), any());
        inOrder.verify(claimService).release("k");
    }

    @Test
    void taskAnnotations_matchCelerysMaxRetries3_andAreAsync() throws Exception {
        Method m = OrderNotificationService.class.getMethod("sendOrderNotificationTask", String.class, UUID.class, String.class);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();
        Retryable retryable = m.getAnnotation(Retryable.class);
        // max_retries=3 -> 1 initial run + 3 retries
        assertThat(retryable.maxAttempts()).isEqualTo(4);
        assertThat(retryable.backoff().delayExpression()).contains("30000"); // default_retry_delay=30
    }

    @Test
    void emailSubjects_andStatusTexts_areDjangosVerbatim() {
        assertThat(OrderEmailService.subjectFor("CANCELLED_EXPIRED"))
                .isEqualTo("Đơn hàng đã bị huỷ do quá hạn thanh toán / Order cancelled (payment timeout)");
        assertThat(OrderEmailService.subjectFor("WHATEVER")).isEqualTo("Cập nhật đơn hàng / Order update");
        assertThat(OrderEmailService.statusTextFor("WHATEVER")).isEqualTo("đã chuyển sang trạng thái WHATEVER");
    }
}
