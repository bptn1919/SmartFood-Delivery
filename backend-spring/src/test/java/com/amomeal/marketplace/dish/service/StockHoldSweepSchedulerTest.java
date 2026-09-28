package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.config.StockProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the Celery-replacement scheduler ported from
 * ../../backend/dish/tasks.py::release_expired_stock_holds.
 *
 * <p>The interval assertion is the important one: Django's
 * {@code marketplace/celery.py} beat_schedule runs this task every 30.0 seconds,
 * and nothing else in the codebase would catch a drift in that number.
 */
@ExtendWith(MockitoExtension.class)
class StockHoldSweepSchedulerTest {

    @Mock private StockReservationService stockReservationService;
    @Mock private ExpiredOrderHandler expiredOrderHandler;

    private StockHoldSweepScheduler scheduler() {
        return new StockHoldSweepScheduler(stockReservationService, expiredOrderHandler, new StockProperties());
    }

    @Test
    void scheduledIntervalMatchesDjangoBeatSchedule_thirtySeconds() throws Exception {
        Method method = StockHoldSweepScheduler.class.getMethod("releaseExpiredStockHolds");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("the sweep must actually be scheduled").isNotNull();
        assertThat(Duration.parse(scheduled.fixedDelayString()))
                .as("marketplace/celery.py beat_schedule: 30.0 seconds")
                .isEqualTo(Duration.ofSeconds(30));
        // fixedDelay, not fixedRate: a slow tick must not stack up concurrent sweeps.
        assertThat(scheduled.fixedRate()).isEqualTo(-1);
    }

    @Test
    void defaultBatchSizeMatchesDjangosFiveHundredRowSlice() {
        assertThat(scheduler().batchSize()).isEqualTo(500);
    }

    @Test
    void sweepOnce_withNoExpiredHolds_doesNotCallTheOrderHandler() {
        when(stockReservationService.releaseExpiredHolds()).thenReturn(Set.of());

        assertThat(scheduler().sweepOnce()).isZero();
        verify(expiredOrderHandler, never()).cancelExpiredOrders(any());
    }

    @Test
    void sweepOnce_forwardsTheTouchedOrderUidsToTheHandler_andReturnsItsCount() {
        Set<UUID> touched = Set.of(UUID.randomUUID(), UUID.randomUUID());
        when(stockReservationService.releaseExpiredHolds()).thenReturn(touched);
        when(expiredOrderHandler.cancelExpiredOrders(touched)).thenReturn(2);

        assertThat(scheduler().sweepOnce()).isEqualTo(2);
        verify(expiredOrderHandler).cancelExpiredOrders(touched);
    }

    @Test
    void scheduledEntryPointSwallowsFailures_soTheSweepKeepsTicking() {
        // Spring's scheduler drops a task that throws and (unlike Celery) never
        // retries it — the wrapper must log instead of propagating.
        when(stockReservationService.releaseExpiredHolds()).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> scheduler().releaseExpiredStockHolds()).doesNotThrowAnyException();
    }

    @Test
    void defaultExpiredOrderHandler_reportsZeroCancellations_untilOrderIsPorted() {
        // PORT-NOTE: steps 2 and 3 of the Django task (cancel the order, expire its
        // vouchers, notify the customer) belong to `order`/`voucher`, which are not
        // ported. The stock-side release is complete; this default only logs.
        LoggingExpiredOrderHandler handler = new LoggingExpiredOrderHandler();
        assertThat(handler.cancelExpiredOrders(Set.of(UUID.randomUUID()))).isZero();
    }
}
