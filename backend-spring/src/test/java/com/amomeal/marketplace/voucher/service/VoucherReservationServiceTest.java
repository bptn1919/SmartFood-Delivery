package com.amomeal.marketplace.voucher.service;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.exception.VoucherInvalidException;
import com.amomeal.marketplace.voucher.exception.VoucherNotFoundException;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
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
 * Full-stack tests for {@link VoucherService}'s reservation lifecycle against a <b>real
 * Postgres</b> (via the shared {@link TestcontainersConfiguration}) — nothing is mocked,
 * because the whole point of this class is proving the {@code SELECT ... FOR UPDATE} row lock
 * (see {@link VoucherRepository#findShopVoucherForUpdate}) actually serializes concurrent
 * usage-limit checks the way a mocked repository cannot, mirroring
 * {@code dish.service.StockReservationServiceTest}'s multi-thread proof for Redis-backed stock
 * holds — except this reservation system is pure Postgres (see {@link VoucherService}'s class
 * javadoc for why there's no Redis involved here at all).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class VoucherReservationServiceTest {

    @Autowired
    private VoucherService service;
    @Autowired
    private VoucherRepository voucherRepository;
    @Autowired
    private AppliedVoucherRepository appliedVoucherRepository;
    @Autowired
    private CustomUserRepository customUserRepository;

    private CustomUser chef;

    @BeforeEach
    void setUp() {
        chef = customUserRepository.save(CustomUser.builder()
                .username("chef-" + UUID.randomUUID())
                .email("chef-" + UUID.randomUUID() + "@test.com")
                .password("x")
                .build());
    }

    private CustomUser newCustomer() {
        return customUserRepository.save(CustomUser.builder()
                .username("cust-" + UUID.randomUUID())
                .email("cust-" + UUID.randomUUID() + "@test.com")
                .password("x")
                .build());
    }

    private Voucher newShopVoucher(Integer usageLimit, int usageLimitPerUser) {
        return voucherRepository.save(Voucher.builder()
                .chef(chef)
                .code("SALE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .name("Sale")
                .voucherType(VoucherType.SHOP_VOUCHER)
                .discountType(VoucherDiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("10"))
                .minOrderAmount(BigDecimal.ZERO)
                .startDate(Instant.now().minus(1, ChronoUnit.DAYS))
                .endDate(Instant.now().plus(30, ChronoUnit.DAYS))
                .usageLimit(usageLimit)
                .usageLimitPerUser(usageLimitPerUser)
                .isActive(true)
                .build());
    }

    // =====================================================================
    // Concurrency -- the reason the voucher row is locked at all
    // =====================================================================

    @Test
    void applyShopVoucherReservation_isSerializedUnderConcurrency_neverExceedsUsageLimit() throws Exception {
        int usageLimit = 5;
        int contenders = 25;
        Voucher voucher = newShopVoucher(usageLimit, 1);

        List<CustomUser> customers = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            customers.add(newCustomer());
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            for (CustomUser customer : customers) {
                UUID orderUid = UUID.randomUUID();
                pool.submit(() -> {
                    try {
                        startGate.await();
                        service.applyShopVoucherReservation(
                                customer, orderUid, null, chef, new BigDecimal("200000"), voucher.getCode());
                        succeeded.incrementAndGet();
                    } catch (VoucherInvalidException ex) {
                        rejected.incrementAndGet();
                    } catch (Exception ex) {
                        // any other failure is a real bug -- surfaces as neither bucket, failing
                        // the count assertions below loudly instead of silently passing.
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

        assertThat(succeeded.get()).isEqualTo(usageLimit);
        assertThat(rejected.get()).isEqualTo(contenders - usageLimit);

        long reservedRows = appliedVoucherRepository.countByVoucherAndStatusIn(
                voucher, Set.of(VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED));
        assertThat(reservedRows).isEqualTo(usageLimit);
    }

    @Test
    void applyShopVoucherReservation_perUserLimitIsAlsoSerialized_sameOrderRepeatedReuses() {
        Voucher voucher = newShopVoucher(null, 1);
        CustomUser customer = newCustomer();
        UUID orderUid = UUID.randomUUID();

        // First call creates a RESERVED row.
        AppliedVoucher first = service.applyShopVoucherReservation(
                customer, orderUid, null, chef, new BigDecimal("100000"), voucher.getCode());
        assertThat(first.getStatus()).isEqualTo(VoucherReservationStatus.RESERVED);

        // Second call for the SAME order reuses (not duplicates) the reservation -- recalculates
        // discount against the new subtotal instead of creating a second row. (Not asserting a
        // global appliedVoucherRepository.count() here -- this Postgres instance is shared
        // across the whole test class/run, so a global count isn't isolated to this test.)
        AppliedVoucher second = service.applyShopVoucherReservation(
                customer, orderUid, null, chef, new BigDecimal("300000"), voucher.getCode());
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getDiscountAmount()).isEqualByComparingTo("30000");

        long reservedForThisVoucher = appliedVoucherRepository.countByVoucherAndStatusIn(
                voucher, Set.of(VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED));
        assertThat(reservedForThisVoucher).isEqualTo(1);
    }

    @Test
    void applyShopVoucherReservation_perUserLimitExceeded_secondOrderForSameUserRejected() {
        Voucher voucher = newShopVoucher(null, 1);
        CustomUser customer = newCustomer();

        service.applyShopVoucherReservation(customer, UUID.randomUUID(), null, chef, new BigDecimal("100000"), voucher.getCode());

        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, UUID.randomUUID(), null, chef, new BigDecimal("100000"), voucher.getCode()))
                .isInstanceOf(VoucherInvalidException.class);
    }

    @Test
    void applyShopVoucherReservation_wrongChef_isNotFound() {
        Voucher voucher = newShopVoucher(null, 1);
        CustomUser otherChef = customUserRepository.save(CustomUser.builder()
                .username("other-chef-" + UUID.randomUUID()).email("other-" + UUID.randomUUID() + "@test.com")
                .password("x").build());
        CustomUser customer = newCustomer();

        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, UUID.randomUUID(), null, otherChef, BigDecimal.TEN, voucher.getCode()))
                .isInstanceOf(VoucherNotFoundException.class);
    }

    /**
     * PORT-NOTE: a real, reachable Django bug — see {@code AppliedVoucher}'s class javadoc and
     * {@code V9__init_voucher.sql}'s comment. The partial unique index on
     * {@code (order_uid, voucher_type) WHERE voucher_type = 'SHOP_VOUCHER'} applies regardless
     * of {@code status}, so once a reservation for an order EXPIRES, a second attempt to
     * reserve (even a different voucher code) for that same order can never insert — it
     * collides with the now-stale EXPIRED row. Preserved verbatim, not fixed.
     */
    @Test
    void applyShopVoucherReservation_afterExpiry_secondAttemptForSameOrder_hitsThePreservedUniqueConstraintBug() {
        Voucher voucher = newShopVoucher(null, 5);
        CustomUser customer = newCustomer();
        UUID orderUid = UUID.randomUUID();

        AppliedVoucher reservation = service.applyShopVoucherReservation(
                customer, orderUid, null, chef, new BigDecimal("100000"), voucher.getCode());

        // Force it into the past so expireOldReservations sweeps it to EXPIRED on the next call.
        reservation.setReservationExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        appliedVoucherRepository.save(reservation);

        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, orderUid, null, chef, new BigDecimal("100000"), voucher.getCode()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
