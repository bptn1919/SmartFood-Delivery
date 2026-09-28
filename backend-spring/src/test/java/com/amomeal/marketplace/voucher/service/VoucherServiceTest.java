package com.amomeal.marketplace.voucher.service;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.dto.CreateVoucherRequest;
import com.amomeal.marketplace.voucher.dto.UpdateVoucherRequest;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.exception.VoucherCodeAlreadyExistsException;
import com.amomeal.marketplace.voucher.exception.VoucherInvalidException;
import com.amomeal.marketplace.voucher.exception.VoucherNotFoundException;
import com.amomeal.marketplace.voucher.exception.VoucherNotOwnedException;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests (Mockito) for {@link VoucherService} — business logic mirrored 1:1 from
 * ../../backend/voucher/services/__init__.py, in particular: create/update validation order,
 * the ADMIN-group-OR-{@code is_staff} ownership bypass (the one place {@code is_staff}
 * genuinely still matters for authorization, per PROGRESS.md "Part 1 findings"), the
 * validity-window / min-order-amount / usage-limit precedence in
 * {@code validateVoucherForOrder}, and the reservation-apply methods' quota-then-validity
 * check order. Real concurrency (the row-lock proving no oversell of a usage-limited voucher)
 * is proven separately against a real Postgres in {@code VoucherReservationServiceTest},
 * mirroring how {@code dish}'s Redis-backed stock holds are proven in
 * {@code StockReservationServiceTest} — a mocked repository here cannot prove a DB-level
 * pessimistic lock actually serializes concurrent transactions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoucherServiceTest {

    @Mock private VoucherRepository voucherRepository;
    @Mock private AppliedVoucherRepository appliedVoucherRepository;

    private VoucherService service;

    private CustomUser chef;
    private CustomUser otherChef;
    private CustomUser admin;
    private CustomUser staffChef;
    private CustomUser customer;

    @BeforeEach
    void setUp() {
        service = new VoucherService(voucherRepository, appliedVoucherRepository);

        chef = CustomUser.builder().id(1L).username("chef").email("chef@test.com").build();
        otherChef = CustomUser.builder().id(2L).username("other-chef").email("other@test.com").build();
        customer = CustomUser.builder().id(3L).username("customer").email("customer@test.com").build();
        chef.addRole(com.amomeal.marketplace.users.entity.UserRole.CHEF);
        otherChef.addRole(com.amomeal.marketplace.users.entity.UserRole.CHEF);
        customer.addRole(com.amomeal.marketplace.users.entity.UserRole.CUSTOMER);

        admin = CustomUser.builder().id(4L).username("admin").email("admin@test.com").build();
        admin.addRole(com.amomeal.marketplace.users.entity.UserRole.ADMIN);

        // The is_staff-as-OR-fallback quirk: a CHEF with no ADMIN group membership at all,
        // but isStaff=true, must still be treated as "admin" for ownership bypass purposes —
        // voucher/services/__init__.py:161,197: `chef.groups.filter(name="ADMIN").exists() or chef.is_staff`.
        staffChef = CustomUser.builder().id(5L).username("staff-chef").email("staff@test.com").isStaff(true).build();
        staffChef.addRole(com.amomeal.marketplace.users.entity.UserRole.CHEF);
    }

    private Voucher voucherOwnedBy(CustomUser owner) {
        return Voucher.builder()
                .uid(UUID.randomUUID())
                .chef(owner)
                .code("SUMMER10")
                .name("Summer sale")
                .voucherType(VoucherType.SHOP_VOUCHER)
                .discountType(VoucherDiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("10"))
                .minOrderAmount(BigDecimal.ZERO)
                .startDate(Instant.now().minus(1, ChronoUnit.DAYS))
                .endDate(Instant.now().plus(30, ChronoUnit.DAYS))
                .usageLimitPerUser(1)
                .isActive(true)
                .build();
    }

    // =====================================================================
    // createVoucher
    // =====================================================================

    @Test
    void createVoucher_forcesShopVoucherType_regardlessOfInput() {
        when(voucherRepository.existsByCodeIgnoreCase("NEWCODE")).thenReturn(false);
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateVoucherRequest payload = new CreateVoucherRequest(
                "newcode", "New voucher", null, VoucherDiscountType.PERCENTAGE,
                new BigDecimal("10"), null, null, Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS),
                null, null, null);

        Voucher created = service.createVoucher(chef, payload);

        assertThat(created.getVoucherType()).isEqualTo(VoucherType.SHOP_VOUCHER);
        assertThat(created.getCode()).isEqualTo("NEWCODE"); // uppercased
        assertThat(created.getUsageLimitPerUser()).isEqualTo(1); // default
        assertThat(created.getMinOrderAmount()).isEqualByComparingTo(BigDecimal.ZERO); // default
        assertThat(created.isActive()).isTrue(); // default
    }

    @Test
    void createVoucher_isChefOrAdminOnly_customerGets403_adminAllowed() {
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreateVoucherRequest payload = new CreateVoucherRequest(
                "adm", "x", null, VoucherDiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS), null, null, null);

        assertThatThrownBy(() -> service.createVoucher(customer, payload))
                .isInstanceOf(com.amomeal.marketplace.voucher.exception.VoucherNotOwnedException.class);
        verify(voucherRepository, never()).save(any());
        assertThat(service.createVoucher(admin, payload).getCode()).isEqualTo("ADM");
    }

    @Test
    void createVoucher_duplicateCode_throwsEvenAcrossDifferentChefs() {
        // Django's check_code_exists has no chef filter -- global uniqueness. Stubbed with
        // any() rather than the exact literal: the real Spring Data `existsByCodeIgnoreCase`
        // is case-insensitive at the DB level, which a literal-string Mockito stub can't
        // simulate on its own (the service passes the payload's code through unmodified).
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(true);

        CreateVoucherRequest payload = new CreateVoucherRequest(
                "summer10", "x", null, VoucherDiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS), null, null, null);

        assertThatThrownBy(() -> service.createVoucher(otherChef, payload))
                .isInstanceOf(VoucherCodeAlreadyExistsException.class);
    }

    @Test
    void createVoucher_startDateNotBeforeEndDate_throws() {
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
        Instant now = Instant.now();
        CreateVoucherRequest payload = new CreateVoucherRequest(
                "X", "x", null, VoucherDiscountType.FIXED_AMOUNT, new BigDecimal("1000"),
                null, null, now, now, null, null, null);

        assertThatThrownBy(() -> service.createVoucher(chef, payload))
                .isInstanceOf(VoucherInvalidException.class)
                .hasMessage("Voucher không hợp lệ");
    }

    @Test
    void createVoucher_percentageOutOfBounds_throws() {
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
        Instant now = Instant.now();
        CreateVoucherRequest tooHigh = new CreateVoucherRequest(
                "X", "x", null, VoucherDiscountType.PERCENTAGE, new BigDecimal("150"),
                null, null, now, now.plus(1, ChronoUnit.DAYS), null, null, null);
        CreateVoucherRequest zero = new CreateVoucherRequest(
                "Y", "y", null, VoucherDiscountType.PERCENTAGE, BigDecimal.ZERO,
                null, null, now, now.plus(1, ChronoUnit.DAYS), null, null, null);

        assertThatThrownBy(() -> service.createVoucher(chef, tooHigh)).isInstanceOf(VoucherInvalidException.class);
        assertThatThrownBy(() -> service.createVoucher(chef, zero)).isInstanceOf(VoucherInvalidException.class);
    }

    @Test
    void createVoucher_fixedAmountAnyValue_isNeverBoundsChecked() {
        // Only PERCENTAGE is bounds-checked in Django -- FIXED_AMOUNT of any size is accepted.
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Instant now = Instant.now();
        CreateVoucherRequest payload = new CreateVoucherRequest(
                "X", "x", null, VoucherDiscountType.FIXED_AMOUNT, new BigDecimal("999999999"),
                null, null, now, now.plus(1, ChronoUnit.DAYS), null, null, null);

        Voucher created = service.createVoucher(chef, payload);
        assertThat(created.getDiscountValue()).isEqualByComparingTo("999999999");
    }

    // =====================================================================
    // updateVoucher / deleteVoucher -- ownership + the ADMIN-or-is_staff quirk
    // =====================================================================

    @Test
    void updateVoucher_ownerCanUpdateTheirOwnVoucher() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateVoucherRequest payload = new UpdateVoucherRequest(
                "Renamed", null, null, null, null, null, null, null, null, null);
        Voucher updated = service.updateVoucher(voucher.getUid(), chef, payload);

        assertThat(updated.getName()).isEqualTo("Renamed");
    }

    @Test
    void updateVoucher_nonOwnerNonAdmin_throwsNotOwned() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));

        UpdateVoucherRequest payload = new UpdateVoucherRequest(
                "Renamed", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateVoucher(voucher.getUid(), otherChef, payload))
                .isInstanceOf(VoucherNotOwnedException.class);
    }

    @Test
    void updateVoucher_plainAdminGroupMember_bypassesOwnership() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateVoucherRequest payload = new UpdateVoucherRequest(
                "Renamed by admin", null, null, null, null, null, null, null, null, null);
        Voucher updated = service.updateVoucher(voucher.getUid(), admin, payload);

        assertThat(updated.getName()).isEqualTo("Renamed by admin");
    }

    @Test
    void updateVoucher_isStaffTrueWithoutAdminGroup_alsoBypassesOwnership() {
        // The specific OR-fallback: voucher/services/__init__.py:161 --
        // `chef.groups.filter(name="ADMIN").exists() or chef.is_staff`.
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(staffChef.isAdmin()).isFalse(); // sanity: no ADMIN group

        UpdateVoucherRequest payload = new UpdateVoucherRequest(
                "Renamed by staff chef", null, null, null, null, null, null, null, null, null);
        Voucher updated = service.updateVoucher(voucher.getUid(), staffChef, payload);

        assertThat(updated.getName()).isEqualTo("Renamed by staff chef");
    }

    @Test
    void updateVoucher_percentageBoundsAreNeverRevalidated_evenWithAnInvalidValue() {
        // PORT-NOTE (see VoucherService.updateVoucher javadoc): UpdateVoucherSchema has no
        // discount_type/voucher_type field, so Django's own bounds-check is dead code here too.
        Voucher voucher = voucherOwnedBy(chef); // PERCENTAGE type
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateVoucherRequest payload = new UpdateVoucherRequest(
                null, null, new BigDecimal("500"), null, null, null, null, null, null, null);
        Voucher updated = service.updateVoucher(voucher.getUid(), chef, payload);

        assertThat(updated.getDiscountValue()).isEqualByComparingTo("500"); // not rejected
    }

    @Test
    void updateVoucher_nullFieldsLeaveExistingValuesUnchanged() {
        Voucher voucher = voucherOwnedBy(chef);
        BigDecimal originalMax = new BigDecimal("50000");
        voucher.setMaxDiscountAmount(originalMax);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Every field null except name -- maxDiscountAmount cannot be cleared this way.
        UpdateVoucherRequest payload = new UpdateVoucherRequest(
                "Only name changes", null, null, null, null, null, null, null, null, null);
        Voucher updated = service.updateVoucher(voucher.getUid(), chef, payload);

        assertThat(updated.getMaxDiscountAmount()).isEqualByComparingTo(originalMax);
    }

    @Test
    void deleteVoucher_nonOwnerNonAdmin_throws_andOwnerSucceeds() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.deleteVoucher(voucher.getUid(), otherChef))
                .isInstanceOf(VoucherNotOwnedException.class);

        service.deleteVoucher(voucher.getUid(), chef);
        verify(voucherRepository, times(1)).delete(voucher);
    }

    @Test
    void deleteVoucher_isStaffTrueWithoutAdminGroup_alsoBypassesOwnership() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findById(voucher.getUid())).thenReturn(Optional.of(voucher));

        service.deleteVoucher(voucher.getUid(), staffChef);
        verify(voucherRepository).delete(voucher);
    }

    @Test
    void getVoucherByUid_notFound_throws() {
        UUID uid = UUID.randomUUID();
        when(voucherRepository.findById(uid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getVoucherByUid(uid)).isInstanceOf(VoucherNotFoundException.class);
    }

    // =====================================================================
    // validateVoucherForOrder -- precedence order
    // =====================================================================

    @Test
    void validate_codeNotFound() {
        when(voucherRepository.findFirstByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        VoucherValidationResult result = service.validateVoucherForOrder("NOPE", BigDecimal.TEN, 1L, customer);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Mã voucher không tồn tại");
        assertThat(result.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void validate_chefMismatch() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findFirstByCodeIgnoreCase(voucher.getCode())).thenReturn(Optional.of(voucher));

        VoucherValidationResult result = service.validateVoucherForOrder(voucher.getCode(), BigDecimal.TEN, otherChef.getId(), customer);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Voucher này không áp dụng cho chef của đơn hàng");
    }

    @Test
    void validate_inactiveVoucher() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setActive(false);
        when(voucherRepository.findFirstByCodeIgnoreCase(voucher.getCode())).thenReturn(Optional.of(voucher));

        VoucherValidationResult result = service.validateVoucherForOrder(voucher.getCode(), BigDecimal.TEN, chef.getId(), customer);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Voucher không còn hoạt động");
    }

    @Test
    void validate_notYetStarted_andAlreadyEnded() {
        Voucher notStarted = voucherOwnedBy(chef);
        notStarted.setStartDate(Instant.now().plus(1, ChronoUnit.DAYS));
        when(voucherRepository.findFirstByCodeIgnoreCase("A")).thenReturn(Optional.of(notStarted));
        assertThat(service.validateVoucherForOrder("A", BigDecimal.TEN, chef.getId(), customer).message())
                .isEqualTo("Voucher chưa đến ngày hiệu lực");

        Voucher ended = voucherOwnedBy(chef);
        ended.setEndDate(Instant.now().minus(1, ChronoUnit.DAYS));
        when(voucherRepository.findFirstByCodeIgnoreCase("B")).thenReturn(Optional.of(ended));
        assertThat(service.validateVoucherForOrder("B", BigDecimal.TEN, chef.getId(), customer).message())
                .isEqualTo("Voucher đã hết hạn");
    }

    @Test
    void validate_totalUsageLimitReached() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setUsageLimit(5);
        when(voucherRepository.findFirstByCodeIgnoreCase(voucher.getCode())).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(5L);

        VoucherValidationResult result = service.validateVoucherForOrder(voucher.getCode(), BigDecimal.TEN, chef.getId(), customer);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Voucher đã hết lượt sử dụng");
    }

    @Test
    void validate_perUserLimitReached_usesTheNonStatusFilteredCount() {
        Voucher voucher = voucherOwnedBy(chef); // usageLimitPerUser = 1
        when(voucherRepository.findFirstByCodeIgnoreCase(voucher.getCode())).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(0L);
        // Even a CANCELLED-only history counts here (no status filter) -- Django quirk.
        when(appliedVoucherRepository.countByVoucherAndUser(voucher, customer)).thenReturn(1L);

        VoucherValidationResult result = service.validateVoucherForOrder(voucher.getCode(), BigDecimal.TEN, chef.getId(), customer);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Bạn đã sử dụng hết lượt cho voucher này");
    }

    @Test
    void validate_belowMinOrderAmount() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setMinOrderAmount(new BigDecimal("100000"));
        when(voucherRepository.findFirstByCodeIgnoreCase(voucher.getCode())).thenReturn(Optional.of(voucher));

        VoucherValidationResult result = service.validateVoucherForOrder(voucher.getCode(), new BigDecimal("50000"), chef.getId(), customer);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Đơn hàng tối thiểu phải từ 100,000đ");
    }

    @Test
    void validate_allChecksPass_returnsComputedDiscount() {
        Voucher voucher = voucherOwnedBy(chef); // 10% PERCENTAGE, no cap
        when(voucherRepository.findFirstByCodeIgnoreCase(voucher.getCode())).thenReturn(Optional.of(voucher));

        VoucherValidationResult result = service.validateVoucherForOrder(voucher.getCode(), new BigDecimal("200000"), chef.getId(), customer);
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isEqualTo("Voucher hợp lệ");
        assertThat(result.discountAmount()).isEqualByComparingTo("20000");
    }

    // =====================================================================
    // Voucher.calculateDiscount / calculateShippingDiscount (entity-level, exercised via
    // the service's discount computation)
    // =====================================================================

    @Test
    void calculateDiscount_fixedAmountCappedAtSubtotal() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setDiscountType(VoucherDiscountType.FIXED_AMOUNT);
        voucher.setDiscountValue(new BigDecimal("100000"));

        assertThat(voucher.calculateDiscount(new BigDecimal("50000"))).isEqualByComparingTo("50000");
        assertThat(voucher.calculateDiscount(new BigDecimal("200000"))).isEqualByComparingTo("100000");
    }

    @Test
    void calculateDiscount_percentageCappedByMaxDiscountAmount() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setDiscountValue(new BigDecimal("50")); // 50%
        voucher.setMaxDiscountAmount(new BigDecimal("30000"));

        assertThat(voucher.calculateDiscount(new BigDecimal("100000"))).isEqualByComparingTo("30000"); // 50000 capped
    }

    @Test
    void calculateDiscount_belowMinOrderAmount_isZero() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setMinOrderAmount(new BigDecimal("100000"));
        assertThat(voucher.calculateDiscount(new BigDecimal("50000"))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculateShippingDiscount_cannotExceedDeliveryFee() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setDiscountValue(new BigDecimal("90")); // 90% of a big delivery fee
        BigDecimal shippingDiscount = voucher.calculateShippingDiscount(new BigDecimal("20000"), new BigDecimal("500000"));
        assertThat(shippingDiscount).isEqualByComparingTo("18000");
        assertThat(shippingDiscount).isLessThanOrEqualTo(new BigDecimal("20000"));
    }

    @Test
    void calculateShippingDiscount_belowMinOrderAmount_isZero() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setMinOrderAmount(new BigDecimal("100000"));
        assertThat(voucher.calculateShippingDiscount(new BigDecimal("20000"), new BigDecimal("50000")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =====================================================================
    // Reservation-apply flows -- quota/validity precedence (Mockito can prove the branching;
    // real row-lock concurrency is proven in VoucherReservationServiceTest)
    // =====================================================================

    @Test
    void applyShopVoucherReservation_notFound_throws() {
        when(voucherRepository.findShopVoucherForUpdate(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, UUID.randomUUID(), null, chef, BigDecimal.TEN, "MISSING"))
                .isInstanceOf(VoucherNotFoundException.class);
    }

    @Test
    void applyShopVoucherReservation_reusesExistingReservation_recalculatingDiscount() {
        Voucher voucher = voucherOwnedBy(chef);
        UUID orderUid = UUID.randomUUID();
        AppliedVoucher existing = AppliedVoucher.builder()
                .id(1L).voucher(voucher).orderUid(orderUid).user(customer)
                .voucherType(VoucherType.SHOP_VOUCHER).discountAmount(BigDecimal.ONE)
                .status(VoucherReservationStatus.RESERVED).build();

        when(voucherRepository.findShopVoucherForUpdate(voucher.getCode(), chef)).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndOrderUidAndUserAndStatus(
                voucher, orderUid, customer, VoucherReservationStatus.RESERVED)).thenReturn(Optional.of(existing));
        when(appliedVoucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppliedVoucher result = service.applyShopVoucherReservation(
                customer, orderUid, null, chef, new BigDecimal("200000"), voucher.getCode());

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("20000"); // 10% of 200000
        assertThat(result.getOrderUid()).isEqualTo(orderUid);
        // Quota checks must NOT run on the reuse path.
        verify(appliedVoucherRepository, never()).countByVoucherAndStatusIn(any(), anyCollection());
    }

    @Test
    void applyShopVoucherReservation_newReservation_totalQuotaExceeded_throws() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setUsageLimit(2);
        when(voucherRepository.findShopVoucherForUpdate(voucher.getCode(), chef)).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndOrderUidAndUserAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(2L);

        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, UUID.randomUUID(), null, chef, BigDecimal.TEN, voucher.getCode()))
                .isInstanceOf(VoucherInvalidException.class);

        verify(appliedVoucherRepository, never()).save(any());
    }

    @Test
    void applyShopVoucherReservation_newReservation_invalidWindow_throwsBeforeUserQuotaCheck() {
        Voucher voucher = voucherOwnedBy(chef);
        voucher.setEndDate(Instant.now().minus(1, ChronoUnit.DAYS)); // expired window
        when(voucherRepository.findShopVoucherForUpdate(voucher.getCode(), chef)).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndOrderUidAndUserAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(0L);

        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, UUID.randomUUID(), null, chef, BigDecimal.TEN, voucher.getCode()))
                .isInstanceOf(VoucherInvalidException.class);

        verify(appliedVoucherRepository, never())
                .countByVoucherAndUserAndStatusIn(any(), any(), anyCollection());
    }

    @Test
    void applyShopVoucherReservation_newReservation_perUserQuotaExceeded_throws() {
        Voucher voucher = voucherOwnedBy(chef);
        when(voucherRepository.findShopVoucherForUpdate(voucher.getCode(), chef)).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndOrderUidAndUserAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(0L);
        when(appliedVoucherRepository.countByVoucherAndUserAndStatusIn(eq(voucher), eq(customer), anyCollection()))
                .thenReturn(1L); // usageLimitPerUser = 1

        assertThatThrownBy(() -> service.applyShopVoucherReservation(
                customer, UUID.randomUUID(), null, chef, BigDecimal.TEN, voucher.getCode()))
                .isInstanceOf(VoucherInvalidException.class);
    }

    @Test
    void applyShopVoucherReservation_newReservation_createsRESERVEDRowWithFifteenMinuteTtl() {
        Voucher voucher = voucherOwnedBy(chef);
        UUID orderUid = UUID.randomUUID();
        when(voucherRepository.findShopVoucherForUpdate(voucher.getCode(), chef)).thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndOrderUidAndUserAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(0L);
        when(appliedVoucherRepository.countByVoucherAndUserAndStatusIn(eq(voucher), eq(customer), anyCollection())).thenReturn(0L);
        ArgumentCaptor<AppliedVoucher> captor = ArgumentCaptor.forClass(AppliedVoucher.class);
        when(appliedVoucherRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        service.applyShopVoucherReservation(customer, orderUid, null, chef, new BigDecimal("200000"), voucher.getCode());

        AppliedVoucher saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(VoucherReservationStatus.RESERVED);
        assertThat(saved.getVoucherType()).isEqualTo(VoucherType.SHOP_VOUCHER);
        assertThat(saved.getOrderUid()).isEqualTo(orderUid);
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("20000");
        assertThat(saved.getReservationExpiresAt())
                .isAfter(before.plusSeconds(14 * 60))
                .isBefore(before.plusSeconds(16 * 60));

        verify(appliedVoucherRepository).expireOldReservations(eq(voucher), any());
    }

    @Test
    void applyPlatformVoucherReservation_rejectsShopVoucherType() {
        assertThatThrownBy(() -> service.applyPlatformVoucherReservation(
                customer, UUID.randomUUID(), "X", VoucherType.SHOP_VOUCHER,
                new PlatformCheckoutSnapshot(BigDecimal.TEN, null, null)))
                .isInstanceOf(VoucherInvalidException.class);
    }

    @Test
    void applyPlatformVoucherReservation_subtotalType_usesNetSubtotalFromSnapshot() {
        Voucher voucher = voucherOwnedBy(null); // platform vouchers have no chef
        voucher.setVoucherType(VoucherType.PLATFORM_SUBTOTAL);
        UUID checkoutUid = UUID.randomUUID();
        when(voucherRepository.findForUpdateByCodeAndVoucherType(voucher.getCode(), VoucherType.PLATFORM_SUBTOTAL))
                .thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndCheckoutUidAndUserAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(0L);
        when(appliedVoucherRepository.countByVoucherAndUserAndStatusIn(eq(voucher), eq(customer), anyCollection())).thenReturn(0L);
        when(appliedVoucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppliedVoucher result = service.applyPlatformVoucherReservation(
                customer, checkoutUid, voucher.getCode(), VoucherType.PLATFORM_SUBTOTAL,
                new PlatformCheckoutSnapshot(new BigDecimal("300000"), null, null));

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("30000"); // 10% of net subtotal
        assertThat(result.getCheckoutUid()).isEqualTo(checkoutUid);
    }

    @Test
    void applyPlatformVoucherReservation_shippingType_usesDeliveryFeeAndCheckoutSubtotalFromSnapshot() {
        Voucher voucher = voucherOwnedBy(null);
        voucher.setVoucherType(VoucherType.PLATFORM_SHIPPING);
        UUID checkoutUid = UUID.randomUUID();
        when(voucherRepository.findForUpdateByCodeAndVoucherType(voucher.getCode(), VoucherType.PLATFORM_SHIPPING))
                .thenReturn(Optional.of(voucher));
        when(appliedVoucherRepository.findFirstByVoucherAndCheckoutUidAndUserAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(appliedVoucherRepository.countByVoucherAndStatusIn(eq(voucher), anyCollection())).thenReturn(0L);
        when(appliedVoucherRepository.countByVoucherAndUserAndStatusIn(eq(voucher), eq(customer), anyCollection())).thenReturn(0L);
        when(appliedVoucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppliedVoucher result = service.applyPlatformVoucherReservation(
                customer, checkoutUid, voucher.getCode(), VoucherType.PLATFORM_SHIPPING,
                new PlatformCheckoutSnapshot(null, new BigDecimal("20000"), new BigDecimal("300000")));

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("2000"); // 10% of 20000 delivery fee
    }

    // =====================================================================
    // calculateNetSubtotal (Django: VoucherService._calculate_net_subtotal)
    // =====================================================================

    @Test
    void calculateNetSubtotal_subtractsAlreadyReservedShopDiscountsPerOrder_flooredAtZero() {
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();
        AppliedVoucherRepository.OrderDiscountTotal totalForOrder1 = discountTotal(order1, new BigDecimal("10000"));
        AppliedVoucherRepository.OrderDiscountTotal totalForOrder2 = discountTotal(order2, new BigDecimal("999999")); // exceeds subtotal
        when(appliedVoucherRepository.sumShopDiscountByOrderUidIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(totalForOrder1, totalForOrder2));

        BigDecimal net = service.calculateNetSubtotal(List.of(
                new OrderSubtotal(order1, new BigDecimal("50000")),
                new OrderSubtotal(order2, new BigDecimal("30000"))));

        // order1: 50000 - 10000 = 40000; order2: max(30000 - 999999, 0) = 0
        assertThat(net).isEqualByComparingTo("40000");
    }

    @Test
    void calculateNetSubtotal_emptyList_isZero() {
        assertThat(service.calculateNetSubtotal(List.of())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static AppliedVoucherRepository.OrderDiscountTotal discountTotal(UUID orderUid, BigDecimal total) {
        return new AppliedVoucherRepository.OrderDiscountTotal() {
            @Override
            public UUID getOrderUid() {
                return orderUid;
            }

            @Override
            public BigDecimal getTotal() {
                return total;
            }
        };
    }
}
