package com.amomeal.marketplace.admin.service;

import com.amomeal.marketplace.admin.dto.AdminDtos.*;
import com.amomeal.marketplace.admin.exception.AdminHttpException;
import com.amomeal.marketplace.admin.exception.AdminPermissionDeniedException;
import com.amomeal.marketplace.admin.exception.AdminValidationException;
import com.amomeal.marketplace.admin.repository.AdminChefBankRepository;
import com.amomeal.marketplace.admin.repository.AdminCustomerBankQueries;
import com.amomeal.marketplace.admin.repository.AdminDashboardQueries;
import com.amomeal.marketplace.admin.repository.AdminOrderRepository;
import com.amomeal.marketplace.admin.repository.AdminUserRepository;
import com.amomeal.marketplace.admin.util.AdminParams;
import com.amomeal.marketplace.admin.util.Specs;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateAttachment;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.certificate.service.CertificateReviewHook;
import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.repository.CustomerPaymentInfoRepository;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.exception.VoucherCodeAlreadyExistsException;
import com.amomeal.marketplace.voucher.exception.VoucherInvalidException;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import com.amomeal.marketplace.voucher.service.VoucherService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Mirrors ../../backend/admin/{services,queries}.py::Service/Query - the admin dashboard aggregating the
 * other modules. It owns no tables. Mutations reuse the owning module's repositories/services
 * ({@link VoucherRepository}/{@link VoucherService}, {@link CertificateRepository} + the
 * {@link CertificateReviewHook} seam, payment's / profile's bank-info entities).
 *
 * <p><b>Authorization</b>: Django's {@code @require_admin} raises {@code PermissionDeniedError} = HTTP 403
 * PERMISSION_DENIED ("Only admin can access this endpoint"), NOT 401 - so it is done here
 * ({@link #requireAdmin}), not with {@code @PreAuthorize} (which the project maps to 401). A missing or bad
 * token is still the standard 401 from the security filter chain.
 *
 * <p>Deliberately NOT class-level {@code @Transactional}: {@link #setCertificateStatus} must commit the
 * status change before the review hook runs (Django autocommit) and the hook's failure must not undo it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminUserRepository userRepository;
    private final AdminOrderRepository orderRepository;
    private final AdminChefBankRepository chefBankRepository;
    private final AdminCustomerBankQueries customerBankQueries;
    private final AdminDashboardQueries dashboard;
    private final CustomUserRepository customUserRepository;
    private final CustomerPaymentInfoRepository customerPaymentInfoRepository;
    private final ChefProfileRepository chefProfileRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherService voucherService;
    private final CertificateRepository certificateRepository;
    private final CertificateAttachmentRepository certificateAttachmentRepository;
    private final CertificateReviewHook certificateReviewHook;
    private final ChefVerificationSessionRepository verificationSessionRepository;
    private final AttachmentRepository attachmentRepository;

    // ================================================================== authorization

    /** Django {@code require_admin}: ADMIN group membership, else 403 PERMISSION_DENIED. */
    public void requireAdmin(CustomUser user) {
        if (user == null || !user.isAdmin()) {
            throw new AdminPermissionDeniedException("Only admin can access this endpoint");
        }
    }

    // ================================================================== helpers

    private static String fullNameOrUsername(CustomUser u) {
        String first = u.getFirstName() == null ? "" : u.getFirstName();
        String last = u.getLastName() == null ? "" : u.getLastName();
        String full = (first + " " + last).strip();
        return full.isEmpty() ? u.getUsername() : full;
    }

    private static double d(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    /** Python {@code round(count / total * 100, 2)} (0 when total is 0). */
    private static double pct(long count, long total) {
        return total > 0 ? PyMath.round((double) count / total * 100, 2) : 0.0;
    }

    private static String isoTime(LocalTime t) {
        String s = String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        int micros = t.getNano() / 1000;
        return micros == 0 ? s : s + "." + String.format("%06d", micros);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Django {@code Paginator} semantics (ninja {@code Pagination}): empty result = 1 page; a page past the
     * end = empty content; page &lt; 1 or page_size &lt;= 0 = uncaught error (500).
     */
    private static <T> PageResponse<T> paginate(int page, int pageSize, Function<Integer, Page<T>> fetch) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("page_size must be positive");
        }
        Page<T> first = null;
        // one query: fetch the requested page (0 rows for a page past the end); total comes with it
        if (page < 1) {
            throw new IllegalArgumentException("That page number is less than 1");
        }
        first = fetch.apply(page - 1);
        long total = first.getTotalElements();
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        List<T> content = totalPages < page ? List.of() : first.getContent();
        return new PageResponse<>(content, page, pageSize, total, totalPages);
    }

    // ================================================================== users

    @Transactional(readOnly = true)
    public PageResponse<UserListItem> getUsers(String userType, String search, Boolean isActive, String fromDate,
                                               String toDate, int page, int pageSize) {
        Specification<CustomUser> spec = (root, q, cb) -> cb.or(
                cb.isMember(UserRole.CUSTOMER, root.get("roles")),
                cb.isMember(UserRole.CHEF, root.get("roles")));
        if (userType != null) {
            String upper = userType.toUpperCase(Locale.ROOT);
            if (upper.equals("CUSTOMER") || upper.equals("CHEF")) {
                UserRole role = UserRole.valueOf(upper);
                spec = spec.and((root, q, cb) -> cb.isMember(role, root.get("roles")));
            }
        }
        if (search != null) {
            spec = spec.and((root, q, cb) -> cb.or(Specs.icontains(cb, root.get("username"), search),
                    Specs.icontains(cb, root.get("email"), search)));
        }
        if (isActive != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("isActive"), isActive));
        }
        LocalDate from = AdminParams.lenientDate(fromDate);
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("dateJoined"), Specs.startOfDay(from)));
        }
        LocalDate to = AdminParams.lenientDate(toDate);
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.<Instant>get("dateJoined"), Specs.startOfNextDay(to)));
        }
        Specification<CustomUser> finalSpec = spec;
        return paginate(page, pageSize, p -> userRepository.findAll(finalSpec,
                        PageRequest.of(p, pageSize, Sort.by(Sort.Order.desc("dateJoined"), Sort.Order.desc("id"))))
                .map(this::toUserItem));
    }

    private UserListItem toUserItem(CustomUser u) {
        List<String> groups = u.getRoles().stream().sorted().map(Enum::name).toList();
        return new UserListItem(u.getId(), u.getUsername(), u.getEmail(), nz(u.getFirstName()), nz(u.getLastName()),
                u.getPhoneNumber(), u.isActive(), u.isStaff(), groups, PyMath.isoformat(u.getDateJoined()));
    }

    /** Django {@code set_user_active_status}: returns false (NOT a 404) for an unknown id. */
    @Transactional
    public boolean setUserActive(long userId, boolean active) {
        return customUserRepository.findById(userId).map(u -> {
            u.setActive(active);
            customUserRepository.save(u);
            return true;
        }).orElse(false);
    }

    // ================================================================== dashboard

    public DashboardOverview overview() {
        long total = dashboard.totalOrders();
        long cancelled = dashboard.cancelledOrders();
        double rate = total > 0 ? PyMath.round((double) cancelled / total * 100, 2) : 0.0;
        return new DashboardOverview(d(dashboard.totalRevenue()), total,
                dashboard.newUsers(Instant.now().minus(Duration.ofDays(30))), dashboard.activeChefs(), rate);
    }

    public RevenueChartResponse revenueChart(LocalDate from, LocalDate to) {
        List<RevenueChartItem> data = new ArrayList<>();
        for (AdminDashboardQueries.DailyRevenue r : dashboard.dailyRevenue(from, to)) {
            data.add(new RevenueChartItem(r.date().toString(), d(r.revenue()), r.orders()));
        }
        return new RevenueChartResponse(from.toString(), to.toString(), data);
    }

    public PaymentMethodStatsResponse paymentMethodStats() {
        long total = dashboard.collectedTransactionCount();
        List<PaymentMethodStatItem> data = new ArrayList<>();
        for (AdminDashboardQueries.PaymentMethodRow r : dashboard.paymentMethodStats()) {
            data.add(new PaymentMethodStatItem(r.method(), r.count(), pct(r.count(), total), d(r.totalAmount())));
        }
        return new PaymentMethodStatsResponse(total, data);
    }

    public OrderStatusStatsResponse orderStatusStats() {
        long total = dashboard.totalOrders();
        List<OrderStatusStatItem> data = new ArrayList<>();
        for (AdminDashboardQueries.StatusRow r : dashboard.orderStatusStats()) {
            data.add(new OrderStatusStatItem(r.status(), r.count(), pct(r.count(), total)));
        }
        return new OrderStatusStatsResponse(total, data);
    }

    public DistrictStatsResponse successOrdersByDistrict(LocalDate from, LocalDate to) {
        long total = dashboard.completedWithAddressCount(from, to);
        List<DistrictStatItem> data = new ArrayList<>();
        for (AdminDashboardQueries.DistrictRow r : dashboard.successOrdersByDistrict(from, to)) {
            data.add(new DistrictStatItem(r.district(), r.count(), pct(r.count(), total)));
        }
        return new DistrictStatsResponse(total, data);
    }

    /** {@code limit} is already capped at 20 by the caller (Django: {@code min(limit, 20)}). */
    @Transactional(readOnly = true)
    public List<TopChefItem> topChefs(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Negative indexing is not supported.");
        }
        if (limit == 0) {
            return List.of();
        }
        List<TopChefItem> out = new ArrayList<>();
        for (AdminDashboardQueries.TopChefRow r : dashboard.topChefs(limit)) {
            String first = nz(r.firstName());
            String last = nz(r.lastName());
            String full = (first + " " + last).strip();
            String avatar = chefProfileRepository.findByUserId(r.chefId())
                    .map(p -> p.getAvatar() == null ? null : p.getAvatar().getPublicUrl())
                    .filter(u -> !u.isEmpty()).orElse(null);
            out.add(new TopChefItem(r.chefId(), full.isEmpty() ? r.username() : full, r.email(), r.totalOrders(),
                    d(r.totalRevenue()), avatar));
        }
        return out;
    }

    // ================================================================== orders

    @Transactional(readOnly = true)
    public PageResponse<OrderListItem> getOrders(String customerEmail, String chefEmail, String status,
                                                 String paymentStatus, String paymentMethod, String fromDate,
                                                 String toDate, int page, int pageSize) {
        Specification<Order> spec = (root, q, cb) -> {
            boolean fetch = q.getResultType() != Long.class && q.getResultType() != long.class;
            Join<Order, Checkout> checkout = fetch ? castJoin(root.fetch("checkout", JoinType.INNER))
                    : root.join("checkout", JoinType.INNER);
            Join<Order, CustomUser> owner = fetch ? castJoin(root.fetch("owner", JoinType.LEFT))
                    : root.join("owner", JoinType.LEFT);
            Join<Order, CustomUser> chef = fetch ? castJoin(root.fetch("chef", JoinType.LEFT))
                    : root.join("chef", JoinType.LEFT);
            List<Predicate> ps = new ArrayList<>();
            if (customerEmail != null) {
                ps.add(Specs.icontains(cb, owner.get("email"), customerEmail));
            }
            if (chefEmail != null) {
                ps.add(Specs.icontains(cb, chef.get("email"), chefEmail));
            }
            if (status != null) {
                ps.add(enumEq(cb, root.get("status"), OrderStatus.class, status));
            }
            if (paymentStatus != null) {
                ps.add(enumEq(cb, root.get("paymentStatus"), PaymentStatus.class, paymentStatus));
            }
            if (paymentMethod != null) {
                ps.add(enumEq(cb, checkout.get("paymentMethod"), PaymentMethod.class, paymentMethod));
            }
            LocalDate from = AdminParams.lenientDate(fromDate);
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), Specs.startOfDay(from)));
            }
            LocalDate to = AdminParams.lenientDate(toDate);
            if (to != null) {
                ps.add(cb.lessThan(root.<Instant>get("createdAt"), Specs.startOfNextDay(to)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return paginate(page, pageSize, p -> {
            Page<Order> result = orderRepository.findAll(spec,
                    PageRequest.of(p, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("uid"))));
            Map<UUID, String> codes = dashboard.voucherCodesByOrder(result.getContent().stream().map(Order::getUid).toList());
            return result.map(o -> toOrderListItem(o, codes.get(o.getUid())));
        });
    }

    /**
     * Django {@code Q(status=value.upper())}: an unknown value simply matches nothing (no error).
     */
    private static <E extends Enum<E>> Predicate enumEq(jakarta.persistence.criteria.CriteriaBuilder cb,
                                                        jakarta.persistence.criteria.Expression<?> path,
                                                        Class<E> type, String value) {
        try {
            return cb.equal(path, Enum.valueOf(type, value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return cb.disjunction();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <X, Y> Join<X, Y> castJoin(Object fetch) {
        return (Join<X, Y>) (Join) fetch;
    }

    private OrderListItem toOrderListItem(Order o, String voucherCode) {
        CustomUser owner = o.getOwner();
        CustomUser chef = o.getChef();
        Checkout checkout = o.getCheckout();
        BigDecimal totalDiscount = nzd(o.getPlatformSubtotalDiscount()).add(nzd(o.getPlatformShippingDiscount()))
                .add(nzd(o.getShopDiscount()));
        return new OrderListItem(o.getUid(),
                owner == null ? null : fullNameOrUsername(owner), owner == null ? null : owner.getEmail(),
                chef == null ? null : fullNameOrUsername(chef), chef == null ? null : chef.getEmail(),
                d(o.getTotalPrice()), d(o.getPlatformSubtotalDiscount()), d(o.getPlatformShippingDiscount()),
                d(o.getShopDiscount()), totalDiscount.doubleValue(), voucherCode, o.getStatus().name(),
                o.getPaymentStatus().name(), checkout == null ? null : checkout.getPaymentMethod().name(),
                PyMath.isoformat(o.getCreatedAt()),
                checkout != null && checkout.getDeliveryDate() != null ? checkout.getDeliveryDate().toString() : null);
    }

    private static BigDecimal nzd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Django {@code get_order_detail}: an unknown order is a 403 PERMISSION_DENIED "Order not found" (the
     * view raises PermissionDeniedError); a malformed uid is an uncaught error (500).
     */
    @Transactional(readOnly = true)
    public OrderDetail getOrderDetail(String orderUid) {
        UUID uid = UUID.fromString(orderUid);
        Order o = orderRepository.findById(uid).orElseThrow(() -> new AdminPermissionDeniedException("Order not found"));
        Map<UUID, String> codes = dashboard.voucherCodesByOrder(List.of(uid));
        CustomUser owner = o.getOwner();
        CustomUser chef = o.getChef();
        Checkout checkout = o.getCheckout();
        List<OrderItemView> items = o.getItems().stream().sorted(Comparator.comparing(OrderItem::getId))
                .map(i -> new OrderItemView(i.getDishName(), i.getDishImageUrl(), i.getQuantity(), d(i.getPrice()),
                        i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())).doubleValue()))
                .toList();
        BigDecimal totalDiscount = nzd(o.getPlatformSubtotalDiscount()).add(nzd(o.getPlatformShippingDiscount()))
                .add(nzd(o.getShopDiscount()));
        String address = null;
        if (checkout != null && checkout.getDeliveryAddress() != null) {
            CustomerAddress a = checkout.getDeliveryAddress();
            address = a.getStreet() + ", " + a.getWard() + ", " + a.getDistrict() + ", " + a.getCity();
        }
        return new OrderDetail(o.getUid(),
                owner == null ? null : owner.getId(), owner == null ? null : fullNameOrUsername(owner),
                owner == null ? null : owner.getEmail(), owner == null ? null : owner.getPhoneNumber(),
                chef == null ? null : chef.getId(), chef == null ? null : fullNameOrUsername(chef),
                chef == null ? null : chef.getEmail(), items, d(o.getSubTotal()), d(o.getTaxAndFees()),
                d(o.getDeliveryFee()), d(o.getPlatformSubtotalDiscount()), d(o.getPlatformShippingDiscount()),
                d(o.getShopDiscount()), totalDiscount.doubleValue(), codes.get(uid), d(o.getTotalPrice()),
                o.getStatus().name(), o.getPaymentStatus().name(),
                checkout == null ? null : checkout.getPaymentMethod().name(), address,
                checkout != null && checkout.getDeliveryDate() != null ? checkout.getDeliveryDate().toString() : null,
                checkout != null && checkout.getDeliveryTime() != null ? isoTime(checkout.getDeliveryTime()) : null,
                PyMath.isoformat(o.getCreatedAt()), PyMath.isoformat(o.getUpdatedAt()));
    }

    // ================================================================== vouchers

    /**
     * pydantic {@code datetime}: ISO with or without offset; a naive value (FE-admin's datetime-local
     * "2026-01-01T10:00") is UTC (Django TIME_ZONE=UTC); a bare date is midnight.
     */
    public static Instant parseDateTime(String value, String field) {
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new AdminValidationException(field, "Input should be a valid datetime");
        }
    }

    /** Django {@code Service.create_voucher}: same check order (code, dates, percentage, type). */
    @Transactional
    public AdminVoucherResponse createVoucher(CustomUser admin, AdminCreateVoucherRequest p, Instant start, Instant end) {
        requireAdmin(admin);
        if (voucherRepository.existsByCodeIgnoreCase(p.code())) {
            throw new VoucherCodeAlreadyExistsException();
        }
        if (!start.isBefore(end)) {
            throw new VoucherInvalidException("Ngày bắt đầu phải trước ngày kết thúc");
        }
        if (p.discountType() == VoucherDiscountType.PERCENTAGE
                && (p.discountValue().signum() <= 0 || p.discountValue().compareTo(new BigDecimal("100")) > 0)) {
            throw new VoucherInvalidException("Giá trị giảm giá phần trăm phải trong khoảng (0, 100]");
        }
        if (p.voucherType() != VoucherType.PLATFORM_SUBTOTAL && p.voucherType() != VoucherType.PLATFORM_SHIPPING) {
            throw new VoucherInvalidException("Admin chỉ được tạo voucher loại PLATFORM_SUBTOTAL hoặc PLATFORM_SHIPPING");
        }
        Voucher voucher = Voucher.builder()
                .chef(admin)
                .code(p.code().toUpperCase(Locale.ROOT))
                .name(p.name())
                .description(p.description())
                .voucherType(p.voucherType())
                .discountType(p.discountType())
                .discountValue(p.discountValue())
                // Django: `Decimal(str(x)) if payload.max_discount_amount else None` -> 0 also becomes None
                .maxDiscountAmount(p.maxDiscountAmount() == null || p.maxDiscountAmount().signum() == 0
                        ? null : p.maxDiscountAmount())
                .minOrderAmount(p.minOrderAmount() == null ? BigDecimal.ZERO : p.minOrderAmount())
                .startDate(start)
                .endDate(end)
                .usageLimit(p.usageLimit())
                .usageLimitPerUser(p.usageLimitPerUser() == null ? 1 : p.usageLimitPerUser())
                .isActive(p.isActive() == null || p.isActive())
                .build();
        return toVoucherResponse(voucherRepository.save(voucher));
    }

    @Transactional(readOnly = true)
    public List<AdminVoucherResponse> listVouchers(CustomUser admin) {
        requireAdmin(admin);
        return voucherRepository.findAll(Sort.by(Sort.Order.desc("createdAt"))).stream()
                .map(this::toVoucherResponse).toList();
    }

    private AdminVoucherResponse toVoucherResponse(Voucher v) {
        return new AdminVoucherResponse(v.getUid(), v.getCode(), v.getName(), v.getDescription(),
                v.getVoucherType().name(), v.getDiscountType().name(), v.getDiscountValue(),
                v.getMaxDiscountAmount(), v.getMinOrderAmount(), v.getStartDate(), v.getEndDate(),
                v.getUsageLimit(), voucherService.usageCount(v), v.getUsageLimitPerUser(), v.isActive(),
                v.getCreatedAt(), v.getUpdatedAt());
    }

    // ================================================================== certificate review

    /**
     * Django {@code Service.set_certificate_status}: not-found (or soft-deleted) = 404 HTTP_ERROR
     * "Certificate not found"; a malformed uid is an uncaught error (500). After the status is saved the
     * shared review hook runs (Django's {@code _cleanup_selfie_if_fully_reviewed}); its failure is logged
     * and swallowed.
     */
    public boolean setCertificateStatus(CustomUser admin, String certificateUid, CertificateStatus status) {
        UUID uid = UUID.fromString(certificateUid);
        Certificate certificate = certificateRepository.findByUidAndDeletedFalse(uid)
                .orElseThrow(() -> new AdminHttpException(HttpStatus.NOT_FOUND, "Certificate not found"));
        certificate.setStatus(status);
        certificate.setVerifiedBy(customUserRepository.getReferenceById(admin.getId()));
        certificate.setVerifiedAt(Instant.now());
        certificateRepository.save(certificate);
        try {
            certificateReviewHook.afterReviewed(uid);
        } catch (RuntimeException e) {
            log.error("cleanup after admin review failed: {}", e.getMessage());
        }
        return true;
    }

    // ================================================================== verification review

    /** Django {@code get_verification_review_data} (+ the view's 403 when there is no session). */
    @Transactional(readOnly = true)
    public VerificationReview getVerificationReview(long userId) {
        ChefVerificationSession session = verificationSessionRepository.findByUserId(userId)
                .orElseThrow(() -> new AdminPermissionDeniedException("Không tìm thấy phiên xác minh cho user này."));
        CustomUser user = customUserRepository.findById(userId).orElseThrow();

        List<String> cccdUrls = new ArrayList<>();
        for (String uidStr : session.getCccdAttachmentUids() == null ? List.<String>of() : session.getCccdAttachmentUids()) {
            try {
                attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(UUID.fromString(uidStr))
                        .ifPresent(a -> cccdUrls.add(a.getPublicUrl()));
            } catch (RuntimeException ignored) {
                // Django: `except Exception: pass`
            }
        }

        String selfieUrl = null;
        if (session.getSelfieAttachmentUid() != null) {
            Attachment selfie = attachmentRepository.findById(session.getSelfieAttachmentUid()).orElse(null);
            if (selfie != null && !selfie.isFileDeleted()) {
                selfieUrl = selfie.getPublicUrl();
            }
        }

        List<AdminCertificateDetail> certs = new ArrayList<>();
        List<UUID> certUids = new ArrayList<>();
        if (session.getBusinessCertificateUid() != null) {
            certUids.add(session.getBusinessCertificateUid());
        }
        if (session.getFoodSafetyCertificateUid() != null) {
            certUids.add(session.getFoodSafetyCertificateUid());
        }
        for (Certificate cert : certificateRepository.findAllById(certUids).stream()
                .sorted(Comparator.comparingInt(c -> certUids.indexOf(c.getUid()))).toList()) {
            List<CertAttachmentItem> attachments = new ArrayList<>();
            for (CertificateAttachment ca : certificateAttachmentRepository.findByCertificateOrderByPositionAsc(cert)) {
                if (!ca.getAttachment().isFileDeleted()) {
                    attachments.add(new CertAttachmentItem(ca.getAttachment().getUid(), ca.getPosition(),
                            ca.getAttachment().getPublicUrl()));
                }
            }
            certs.add(new AdminCertificateDetail(cert.getUid(), cert.getName(), cert.getCertificateType().name(),
                    cert.getStatus().name(), cert.getIssuedBy(),
                    cert.getIssueDate() == null ? null : cert.getIssueDate().toString(),
                    cert.getExpirationDate() == null ? null : cert.getExpirationDate().toString(),
                    cert.getRejectionReason(),
                    cert.getVerifiedBy() == null ? null : cert.getVerifiedBy().getEmail(),
                    cert.getVerifiedAt() == null ? null : PyMath.isoformat(cert.getVerifiedAt()), attachments));
        }
        return new VerificationReview(user.getId(), user.getEmail(), session.getDecision(), session.getRiskScore(),
                session.getRiskFlags() == null ? List.of() : session.getRiskFlags(), session.getFaceSimilarityScore(),
                session.getVerifiedIdentity(), session.getCccdNumberMasked(), selfieUrl, cccdUrls, certs,
                session.getVerifiedAt() == null ? null : PyMath.isoformat(session.getVerifiedAt()));
    }

    // ================================================================== bank accounts

    /** The two bank-info tables collapsed to what either response schema needs. */
    public record BankRecord(long id, String bankName, String bankCode, String bankBranch, String citizenId,
                             String taxCode, boolean verified, Instant verifiedAt, Instant createdAt,
                             Instant updatedAt, boolean deleted, String accountNumber, String accountHolderName,
                             BankUser user) {
    }

    private BankRecord toRecord(CustomerPaymentInfo b) {
        BankUser user = bankUser(customUserRepository.findById(b.getUserId()).orElse(null));
        return new BankRecord(b.getId(), b.getBankName(), b.getBankCode(), b.getBankBranch(), null, null,
                b.isVerified(), b.getVerifiedAt(), b.getCreatedAt(), b.getUpdatedAt(), false,
                b.getBankAccountNumber(), b.getBankAccountName(), user);
    }

    private BankRecord toRecord(ChefPaymentInfo b) {
        return new BankRecord(b.getId(), b.getBankName(), b.getBankCode(), b.getBankBranch(), b.getCitizenId(),
                b.getTaxCode(), b.isVerified(), b.getVerifiedAt(), b.getCreatedAt(), b.getUpdatedAt(), b.isDeleted(),
                b.getBankAccountNumber(), b.getBankAccountName(), bankUser(b.getUser()));
    }

    private static BankUser bankUser(CustomUser u) {
        return u == null ? null : new BankUser(u.getId(), u.getEmail(), u.getUsername(), nz(u.getFirstName()),
                nz(u.getLastName()));
    }

    public static CustomerBankAccount asCustomerSchema(BankRecord r) {
        return new CustomerBankAccount(r.id(), r.bankName(), r.bankCode(), r.bankBranch(), r.verified(),
                r.verifiedAt(), r.createdAt(), r.updatedAt(), r.accountNumber(), r.accountHolderName(),
                r.user() == null ? null : r.user().email(), r.user());
    }

    public static ChefBankAccount asChefSchema(BankRecord r) {
        return new ChefBankAccount(r.id(), r.bankName(), r.bankCode(), r.bankBranch(), r.citizenId(), r.taxCode(),
                r.verified(), r.verifiedAt(), r.createdAt(), r.updatedAt(), r.deleted(), r.accountNumber(),
                r.accountHolderName(), r.user() == null ? null : r.user().email(), r.user());
    }

    /**
     * Django {@code verify_bank_account}: looks the id up in the CUSTOMER table first, then the (non-deleted)
     * CHEF table - the two id spaces overlap, so a chef account whose id also exists as a customer account is
     * unreachable through either route (preserved). The customer route and the chef route share this method;
     * only the response schema differs.
     */
    @Transactional
    public BankRecord verifyBankAccount(long bankAccountId, boolean status) {
        CustomerPaymentInfo customer = customerPaymentInfoRepository.findById(bankAccountId).orElse(null);
        if (customer != null) {
            customer.setVerified(status);
            customer.setVerifiedAt(status ? Instant.now() : null);
            return toRecord(customerPaymentInfoRepository.save(customer));
        }
        ChefPaymentInfo chef = chefBankRepository.findByIdAndDeletedFalse(bankAccountId).orElse(null);
        if (chef != null) {
            chef.setVerified(status);
            chef.setVerifiedAt(status ? Instant.now() : null);
            return toRecord(chefBankRepository.save(chef));
        }
        throw new AdminHttpException(HttpStatus.NOT_FOUND, "Bank account not found");
    }

    @Transactional(readOnly = true)
    public PageResponse<ChefBankAccount> getChefBankAccounts(Boolean status, String search, int page, int pageSize) {
        Specification<ChefPaymentInfo> spec = (root, q, cb) -> {
            boolean fetch = q.getResultType() != Long.class && q.getResultType() != long.class;
            Join<ChefPaymentInfo, CustomUser> user = fetch ? castJoin(root.fetch("user", JoinType.INNER))
                    : root.join("user", JoinType.INNER);
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (status != null) {
                ps.add(cb.equal(root.get("isVerified"), status));
            }
            if (search != null && !search.isEmpty()) {
                ps.add(cb.or(Specs.icontains(cb, user.get("email"), search),
                        Specs.icontains(cb, user.get("username"), search),
                        Specs.icontains(cb, user.get("firstName"), search),
                        Specs.icontains(cb, user.get("lastName"), search)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return paginate(page, pageSize, p -> chefBankRepository.findAll(spec,
                PageRequest.of(p, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(b -> asChefSchema(toRecord(b))));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerBankAccount> getCustomerBankAccounts(Boolean status, String search, int page,
                                                                     int pageSize) {
        if (pageSize <= 0 || page < 1) {
            throw new IllegalArgumentException("invalid pagination");
        }
        AdminCustomerBankQueries.PageResult result =
                customerBankQueries.page(status, search, (page - 1) * pageSize, pageSize);
        long total = result.total();
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        List<CustomerBankAccount> content = totalPages < page ? List.of() : result.rows().stream()
                .map(r -> asCustomerSchema(new BankRecord(r.info().getId(), r.info().getBankName(),
                        r.info().getBankCode(), r.info().getBankBranch(), null, null, r.info().isVerified(),
                        r.info().getVerifiedAt(), r.info().getCreatedAt(), r.info().getUpdatedAt(), false,
                        r.info().getBankAccountNumber(), r.info().getBankAccountName(), bankUser(r.user())))).toList();
        return new PageResponse<>(content, page, pageSize, total, totalPages);
    }
}
