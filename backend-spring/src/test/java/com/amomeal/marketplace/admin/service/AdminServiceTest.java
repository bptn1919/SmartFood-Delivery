package com.amomeal.marketplace.admin.service;

import com.amomeal.marketplace.admin.dto.AdminDtos.AdminCreateVoucherRequest;
import com.amomeal.marketplace.admin.exception.AdminHttpException;
import com.amomeal.marketplace.admin.exception.AdminPermissionDeniedException;
import com.amomeal.marketplace.admin.exception.AdminValidationException;
import com.amomeal.marketplace.admin.repository.AdminChefBankRepository;
import com.amomeal.marketplace.admin.repository.AdminCustomerBankQueries;
import com.amomeal.marketplace.admin.repository.AdminDashboardQueries;
import com.amomeal.marketplace.admin.repository.AdminOrderRepository;
import com.amomeal.marketplace.admin.repository.AdminUserRepository;
import com.amomeal.marketplace.admin.util.AdminParams;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.certificate.service.CertificateReviewHook;
import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.repository.CustomerPaymentInfoRepository;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.exception.VoucherCodeAlreadyExistsException;
import com.amomeal.marketplace.voucher.exception.VoucherInvalidException;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import com.amomeal.marketplace.voucher.service.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock AdminUserRepository userRepository;
    @Mock AdminOrderRepository orderRepository;
    @Mock AdminChefBankRepository chefBankRepository;
    @Mock AdminCustomerBankQueries customerBankQueries;
    @Mock AdminDashboardQueries dashboard;
    @Mock CustomUserRepository customUserRepository;
    @Mock CustomerPaymentInfoRepository customerPaymentInfoRepository;
    @Mock ChefProfileRepository chefProfileRepository;
    @Mock VoucherRepository voucherRepository;
    @Mock VoucherService voucherService;
    @Mock CertificateRepository certificateRepository;
    @Mock CertificateAttachmentRepository certificateAttachmentRepository;
    @Mock CertificateReviewHook certificateReviewHook;
    @Mock ChefVerificationSessionRepository verificationSessionRepository;
    @Mock AttachmentRepository attachmentRepository;
    @InjectMocks AdminService service;

    private CustomUser admin;

    @BeforeEach
    void setUp() {
        admin = CustomUser.builder().id(1L).username("root").email("root@x.test").build();
        admin.addRole(UserRole.ADMIN);
    }

    private static CustomUser userWith(UserRole... roles) {
        CustomUser u = CustomUser.builder().id(2L).username("u").email("u@x.test").build();
        for (UserRole r : roles) {
            u.addRole(r);
        }
        return u;
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void requireAdmin_403ForEveryNonAdminCombination_passesWhenAdminAmongOtherRoles() {
        for (CustomUser u : new CustomUser[]{userWith(), userWith(UserRole.CUSTOMER), userWith(UserRole.CHEF),
                userWith(UserRole.CUSTOMER, UserRole.CHEF), null}) {
            assertThatThrownBy(() -> service.requireAdmin(u))
                    .isInstanceOfSatisfying(AdminPermissionDeniedException.class, e -> {
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.getMessageCode()).isEqualTo("PERMISSION_DENIED");
                        assertThat(e.getMessage()).isEqualTo("Only admin can access this endpoint");
                    });
        }
        service.requireAdmin(admin);
        service.requireAdmin(userWith(UserRole.CUSTOMER, UserRole.ADMIN));
    }

    @Test
    void isStaffAlone_isNotAdmin() {
        CustomUser staff = userWith(UserRole.CUSTOMER);
        staff.setStaff(true);
        staff.setSuperuser(true);
        assertThatThrownBy(() -> service.requireAdmin(staff)).isInstanceOf(AdminPermissionDeniedException.class);
    }

    // ------------------------------------------------------------------ date parsing

    @Test
    void parseDateTime_acceptsNaiveOffsetZuluAndBareDate_asUtc() {
        assertThat(AdminService.parseDateTime("2026-01-01T10:00", "start_date")).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(AdminService.parseDateTime("2026-01-01T10:00:30", "d")).isEqualTo(Instant.parse("2026-01-01T10:00:30Z"));
        assertThat(AdminService.parseDateTime("2026-01-01 10:00", "d")).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(AdminService.parseDateTime("2026-01-01T10:00:00Z", "d")).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(AdminService.parseDateTime("2026-01-01T17:00:00+07:00", "d")).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(AdminService.parseDateTime("2026-01-01T10:00:00.123Z", "d")).isEqualTo(Instant.parse("2026-01-01T10:00:00.123Z"));
        assertThat(AdminService.parseDateTime("2026-01-01", "d")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThatThrownBy(() -> AdminService.parseDateTime("tomorrow", "start_date"))
                .isInstanceOfSatisfying(AdminValidationException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessageCode()).isEqualTo("VALIDATION_ERROR");
                });
    }

    @Test
    void params_strptimeIsStrictButAcceptsUnpadded_lenientIgnores_pydanticDateRejects() {
        assertThat(AdminParams.strptimeDate("2019-3-5").toString()).isEqualTo("2019-03-05");
        assertThatThrownBy(() -> AdminParams.strptimeDate("2019-02-30")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> AdminParams.strptimeDate("03/05/2019")).isInstanceOf(RuntimeException.class);
        assertThat(AdminParams.lenientDate("nope")).isNull();
        assertThat(AdminParams.lenientDate("2019-03-05")).isNotNull();
        assertThat(AdminParams.dateParam(null, "f")).isNull();
        assertThatThrownBy(() -> AdminParams.dateParam("2019-3-5", "from_date")).isInstanceOf(AdminValidationException.class);
        assertThat(AdminParams.boolParam("TRUE", "f")).isTrue();
        assertThat(AdminParams.boolParam("0", "f")).isFalse();
        assertThat(AdminParams.boolParam(null, "f")).isNull();
        assertThatThrownBy(() -> AdminParams.boolParam("maybe", "f")).isInstanceOf(AdminValidationException.class);
        assertThat(AdminParams.intParam(null, "page", 1)).isEqualTo(1);
        assertThat(AdminParams.intParam(" 7 ", "page", 1)).isEqualTo(7);
        assertThatThrownBy(() -> AdminParams.intParam("x", "page", 1)).isInstanceOf(AdminValidationException.class);
    }

    // ------------------------------------------------------------------ voucher rules

    private static AdminCreateVoucherRequest req(String code, VoucherType type, VoucherDiscountType dt, String value) {
        return new AdminCreateVoucherRequest(code, "name", null, type, dt, new BigDecimal(value), null, null,
                "2026-01-01T00:00", "2026-12-31T00:00", null, null, null);
    }

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-31T00:00:00Z");

    @Test
    void createVoucher_nonAdminIsRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> service.createVoucher(userWith(UserRole.CHEF),
                req("A", VoucherType.PLATFORM_SUBTOTAL, VoucherDiscountType.FIXED_AMOUNT, "1"), START, END))
                .isInstanceOf(AdminPermissionDeniedException.class);
        verifyNoInteractions(voucherRepository);
    }

    @Test
    void createVoucher_duplicateCodeIsCheckedFirst() {
        when(voucherRepository.existsByCodeIgnoreCase("DUP")).thenReturn(true);
        // even with an otherwise-invalid request (SHOP type, reversed dates, 500%), the code check wins
        assertThatThrownBy(() -> service.createVoucher(admin,
                req("DUP", VoucherType.SHOP_VOUCHER, VoucherDiscountType.PERCENTAGE, "500"), END, START))
                .isInstanceOf(VoucherCodeAlreadyExistsException.class);
    }

    @Test
    void createVoucher_orderIsDatesThenPercentageThenType() {
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
        assertThatThrownBy(() -> service.createVoucher(admin,
                req("X", VoucherType.SHOP_VOUCHER, VoucherDiscountType.PERCENTAGE, "500"), START, START))
                .isInstanceOfSatisfying(VoucherInvalidException.class,
                        e -> assertThat(e.getDetail()).isEqualTo("Ngày bắt đầu phải trước ngày kết thúc"));
        assertThatThrownBy(() -> service.createVoucher(admin,
                req("X", VoucherType.SHOP_VOUCHER, VoucherDiscountType.PERCENTAGE, "500"), START, END))
                .isInstanceOfSatisfying(VoucherInvalidException.class,
                        e -> assertThat((String) e.getDetail()).contains("(0, 100]"));
        assertThatThrownBy(() -> service.createVoucher(admin,
                req("X", VoucherType.SHOP_VOUCHER, VoucherDiscountType.PERCENTAGE, "50"), START, END))
                .isInstanceOfSatisfying(VoucherInvalidException.class,
                        e -> assertThat((String) e.getDetail()).contains("PLATFORM_SUBTOTAL"));
        verify(voucherRepository, never()).save(any());
    }

    @Test
    void createVoucher_percentageBoundaries_andFixedAmountIsUnchecked() {
        when(voucherRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
        when(voucherRepository.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            v.setUid(UUID.randomUUID());
            v.setCreatedAt(Instant.now());
            v.setUpdatedAt(Instant.now());
            return v;
        });
        when(voucherService.usageCount(any())).thenReturn(0L);
        assertThat(service.createVoucher(admin, req("lower", VoucherType.PLATFORM_SHIPPING, VoucherDiscountType.PERCENTAGE, "0.01"), START, END)
                .code()).isEqualTo("LOWER");                                             // upper-cased
        service.createVoucher(admin, req("top", VoucherType.PLATFORM_SUBTOTAL, VoucherDiscountType.PERCENTAGE, "100"), START, END);
        assertThatThrownBy(() -> service.createVoucher(admin,
                req("z", VoucherType.PLATFORM_SUBTOTAL, VoucherDiscountType.PERCENTAGE, "0"), START, END))
                .isInstanceOf(VoucherInvalidException.class);
        service.createVoucher(admin, req("fixed0", VoucherType.PLATFORM_SUBTOTAL, VoucherDiscountType.FIXED_AMOUNT, "0"), START, END);
        service.createVoucher(admin, req("fixedneg", VoucherType.PLATFORM_SUBTOTAL, VoucherDiscountType.FIXED_AMOUNT, "-5"), START, END);
    }

    // ------------------------------------------------------------------ users

    @Test
    void setUserActive_returnsFalseForUnknownId_notAnError() {
        when(customUserRepository.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.setUserActive(9L, false)).isFalse();
        verify(customUserRepository, never()).save(any());
    }

    @Test
    void setUserActive_flipsTheFlag() {
        CustomUser u = userWith(UserRole.CUSTOMER);
        when(customUserRepository.findById(2L)).thenReturn(Optional.of(u));
        assertThat(service.setUserActive(2L, false)).isTrue();
        assertThat(u.isActive()).isFalse();
        assertThat(service.setUserActive(2L, true)).isTrue();
        assertThat(u.isActive()).isTrue();
    }

    // ------------------------------------------------------------------ certificate review

    @Test
    void setCertificateStatus_unknownIs404HttpError_andHookRunsAfterSave_andItsFailureIsSwallowed() {
        UUID uid = UUID.randomUUID();
        when(certificateRepository.findByUidAndDeletedFalse(uid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setCertificateStatus(admin, uid.toString(), CertificateStatus.ACTIVE))
                .isInstanceOfSatisfying(AdminHttpException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessageCode()).isEqualTo("HTTP_ERROR");
                    assertThat(e.getDetail()).isEqualTo("Certificate not found");
                });
        verify(certificateReviewHook, never()).afterReviewed(any());

        Certificate cert = Certificate.builder().uid(uid).build();
        when(certificateRepository.findByUidAndDeletedFalse(uid)).thenReturn(Optional.of(cert));
        when(customUserRepository.getReferenceById(1L)).thenReturn(admin);
        doThrow(new IllegalStateException("s3 down")).when(certificateReviewHook).afterReviewed(uid);
        assertThat(service.setCertificateStatus(admin, uid.toString(), CertificateStatus.REVOKED)).isTrue();
        assertThat(cert.getStatus()).isEqualTo(CertificateStatus.REVOKED);
        assertThat(cert.getVerifiedBy()).isSameAs(admin);
        assertThat(cert.getVerifiedAt()).isNotNull();
        verify(certificateRepository).save(cert);
        verify(certificateReviewHook).afterReviewed(uid);
    }

    @Test
    void setCertificateStatus_malformedUidIsAnUncaughtError() {
        assertThatThrownBy(() -> service.setCertificateStatus(admin, "nope", CertificateStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ bank accounts

    @Test
    void verifyBankAccount_customerTableFirst_chefRepositoryIsNotTouched() {
        CustomerPaymentInfo c = CustomerPaymentInfo.builder().id(5L).userId(3L).build();
        when(customerPaymentInfoRepository.findById(5L)).thenReturn(Optional.of(c));
        when(customerPaymentInfoRepository.save(c)).thenReturn(c);
        when(customUserRepository.findById(3L)).thenReturn(Optional.empty());
        var r = service.verifyBankAccount(5L, true);
        assertThat(r.verified()).isTrue();
        assertThat(c.getVerifiedAt()).isNotNull();
        verifyNoInteractions(chefBankRepository);

        service.verifyBankAccount(5L, false);
        assertThat(c.isVerified()).isFalse();
        assertThat(c.getVerifiedAt()).isNull();
    }

    @Test
    void verifyBankAccount_fallsBackToNonDeletedChef_elseHttp404() {
        ChefPaymentInfo chef = ChefPaymentInfo.builder().id(7L).user(userWith(UserRole.CHEF)).bankName("b").bankCode("c")
                .bankAccountNumber("n").bankAccountName("a").build();
        when(customerPaymentInfoRepository.findById(7L)).thenReturn(Optional.empty());
        when(chefBankRepository.findByIdAndDeletedFalse(7L)).thenReturn(Optional.of(chef));
        when(chefBankRepository.save(chef)).thenReturn(chef);
        assertThat(service.verifyBankAccount(7L, true).verified()).isTrue();
        assertThat(chef.getVerifiedAt()).isNotNull();

        when(customerPaymentInfoRepository.findById(8L)).thenReturn(Optional.empty());
        when(chefBankRepository.findByIdAndDeletedFalse(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verifyBankAccount(8L, true)).isInstanceOfSatisfying(AdminHttpException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getDetail()).isEqualTo("Bank account not found");
        });
    }

    // ------------------------------------------------------------------ dashboard maths

    @Test
    void overview_cancellationRateIsPythonRounded_andZeroWhenNoOrders() {
        when(dashboard.totalRevenue()).thenReturn(new BigDecimal("1234.50"));
        when(dashboard.newUsers(any())).thenReturn(3L);
        when(dashboard.activeChefs()).thenReturn(2L);
        when(dashboard.totalOrders()).thenReturn(7L);
        when(dashboard.cancelledOrders()).thenReturn(1L);
        var o = service.overview();
        assertThat(o.totalRevenue()).isEqualTo(1234.5);
        assertThat(o.totalOrders()).isEqualTo(7);
        assertThat(o.newUsers()).isEqualTo(3);
        assertThat(o.activeChefs()).isEqualTo(2);
        assertThat(o.cancellationRate()).isEqualTo(14.29);        // 1/7*100 = 14.2857...

        when(dashboard.totalOrders()).thenReturn(0L);
        when(dashboard.cancelledOrders()).thenReturn(0L);
        assertThat(service.overview().cancellationRate()).isZero();
    }

    @Test
    void orderStatusStats_percentagesRoundHalfEvenLikePython() {
        when(dashboard.totalOrders()).thenReturn(8L);
        when(dashboard.orderStatusStats()).thenReturn(java.util.List.of(
                new AdminDashboardQueries.StatusRow("COMPLETED", 5), new AdminDashboardQueries.StatusRow("CANCELLED", 2),
                new AdminDashboardQueries.StatusRow("PENDING", 1)));
        var r = service.orderStatusStats();
        assertThat(r.totalOrders()).isEqualTo(8);
        assertThat(r.data()).extracting(i -> i.percentage()).containsExactly(62.5, 25.0, 12.5);
    }

    @Test
    void topChefs_negativeLimitIsAnError_zeroIsEmpty() {
        assertThatThrownBy(() -> service.topChefs(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.topChefs(0)).isEmpty();
        verifyNoInteractions(dashboard);
    }

    @Test
    void paginationEdgeCases_pageBelowOneAndZeroPageSizeAreErrors() {
        assertThatThrownBy(() -> service.getUsers(null, null, null, null, null, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getUsers(null, null, null, null, null, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Set.of(1).size()).isEqualTo(1);
    }
}
