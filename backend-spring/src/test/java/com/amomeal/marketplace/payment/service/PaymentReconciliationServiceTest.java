package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.config.PaymentProperties;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test (mocks): candidate window/paging, cursor wrap-around and per-payment error isolation. */
class PaymentReconciliationServiceTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentTx tx = mock(PaymentTx.class);
    private final PaymentProperties properties = new PaymentProperties();
    private final PaymentReconciliationService service =
            new PaymentReconciliationService(repository, paymentService, properties, tx);

    @SuppressWarnings("unchecked")
    PaymentReconciliationServiceTest() {
        when(tx.required(any(Supplier.class))).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
    }

    private static PaymentTransaction payment(long id, long orderCode) {
        return PaymentTransaction.builder().id(id).paymentMethod(PaymentMethod.PAYOS).payosOrderCode(orderCode).build();
    }

    @Test
    void queriesPendingPayosPaymentsInTheMinMaxAgeWindow_withBatchSize() {
        properties.getReconciliation().setMinAge(Duration.ofMinutes(2));
        properties.getReconciliation().setMaxAge(Duration.ofHours(24));
        properties.getReconciliation().setBatchSize(7);
        when(repository.findReconciliationCandidates(any(), any(), any(), any(), anyLong(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        service.reconcileOnce();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> createdAfter = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> createdBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findReconciliationCandidates(eq(PaymentMethod.PAYOS), eq(PaymentStatus.PENDING),
                createdAfter.capture(), createdBefore.capture(), eq(0L), page.capture());
        assertThat(createdBefore.getValue()).isBetween(before.minus(Duration.ofMinutes(2)), after.minus(Duration.ofMinutes(2)));
        assertThat(createdAfter.getValue()).isBetween(before.minus(Duration.ofHours(24)), after.minus(Duration.ofHours(24)));
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
        verify(paymentService, never()).syncPaymentByOrderCode(anyLong());
    }

    @Test
    void oneFailingPayment_doesNotAbortTheBatch() {
        when(repository.findReconciliationCandidates(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(List.of(payment(1, 101), payment(2, 102), payment(3, 103), payment(4, 104)));
        when(paymentService.syncPaymentByOrderCode(101)).thenReturn(Map.of("success", true, "status", "HOLDING"));
        when(paymentService.syncPaymentByOrderCode(102)).thenThrow(new IllegalStateException("PayOS timeout"));
        when(paymentService.syncPaymentByOrderCode(103)).thenReturn(Map.of("success", false, "error", "PayOS 500"));
        when(paymentService.syncPaymentByOrderCode(104)).thenReturn(Map.of("success", true, "status", "PENDING"));

        PaymentReconciliationService.Result result = service.reconcileOnce();

        assertThat(result.examined()).isEqualTo(4);
        assertThat(result.failed()).isEqualTo(2);
        verify(paymentService).syncPaymentByOrderCode(104); // reached despite the earlier failures
    }

    @Test
    void cursorAdvancesBetweenTicks_thenWrapsAround() {
        when(repository.findReconciliationCandidates(any(), any(), any(), any(), eq(0L), any()))
                .thenReturn(List.of(payment(5, 105), payment(9, 109)), List.of(payment(5, 105)));
        // doReturn: a when(...) call would itself hit the eq(0L) stub above (matchers evaluate to 0) and eat an answer
        doReturn(List.of()).when(repository).findReconciliationCandidates(any(), any(), any(), any(), eq(9L), any());
        when(paymentService.syncPaymentByOrderCode(anyLong())).thenReturn(Map.of("success", true, "status", "PENDING"));

        assertThat(service.reconcileOnce().examined()).isEqualTo(2); // ids 5, 9; cursor = 9
        assertThat(service.reconcileOnce().examined()).isEqualTo(1); // nothing after 9 -> wraps, id 5 again
        verify(repository, times(2)).findReconciliationCandidates(any(), any(), any(), any(), eq(0L), any());
        verify(repository).findReconciliationCandidates(any(), any(), any(), any(), eq(9L), any());
    }
}
