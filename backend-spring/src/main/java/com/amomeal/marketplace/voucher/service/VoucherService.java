package com.amomeal.marketplace.voucher.service;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.exception.VoucherCodeAlreadyExistsException;
import com.amomeal.marketplace.voucher.exception.VoucherInvalidException;
import com.amomeal.marketplace.voucher.exception.VoucherNotFoundException;
import com.amomeal.marketplace.voucher.exception.VoucherNotOwnedException;
import com.amomeal.marketplace.voucher.dto.CreateVoucherRequest;
import com.amomeal.marketplace.voucher.dto.UpdateVoucherRequest;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository.OrderDiscountTotal;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mirrors ../../backend/voucher/services/__init__.py::VoucherService — chef-owned
 * {@code SHOP_VOUCHER} CRUD, order-time validation, and the reservation lifecycle that backs
 * checkout (CLAUDE.md flags this module "medium-to-complex" specifically for the reservation
 * system, same reason as {@code dish}'s stock holds).
 *
 * <h2>No Redis here — confirmed, not assumed (see task brief §4)</h2>
 * Grepped {@code voucher/services/__init__.py} and {@code voucher/orm/voucher.py} top to
 * bottom: neither imports redis, a cache client, or anything Redis-adjacent. The reservation
 * system is <b>pure Postgres</b>: {@code Voucher.objects.select_for_update()} (row lock, ported
 * as {@code @Lock(PESSIMISTIC_WRITE)} in {@link VoucherRepository}) plus a durable
 * {@code AppliedVoucher} ledger row with a {@code reservation_expires_at} TTL column, unlike
 * {@code dish}'s stock holds (an atomic Redis counter backed by a Postgres ledger). Expiry is
 * a <b>synchronous, lazy check</b> — {@code expire_old_reservations(voucher)} runs inline at
 * the start of every {@code apply_*_reservation} call, sweeping past-due RESERVED rows for
 * that one voucher to EXPIRED before doing anything else. There is no Celery task, cron, or
 * {@code @Scheduled} job anywhere in Django's {@code voucher} app — the only place voucher rows
 * get touched by a background job at all is {@code dish/tasks.py::release_expired_stock_holds}
 * (confirmed against {@code marketplace/celery.py}'s {@code beat_schedule}, which lists exactly
 * the two tasks CLAUDE.md §6 already accounts for), and that task's own voucher-expiring step
 * (step 2: cancel DRAFT/PENDING orders + expire their AppliedVoucher rows) is {@code order}
 * territory, already flagged as unimplemented in {@code dish}'s own PROGRESS.md entry — nothing
 * new to add here.
 *
 * <h2>Reservation transitions this module does NOT perform</h2>
 * RESERVED -> USED (on checkout/payment confirm) and RESERVED/USED -> CANCELLED (on order
 * cancel) are written directly by {@code order/services/__init__.py} via raw Django ORM
 * {@code AppliedVoucher.objects.filter(...).update(status=...)} calls — {@code voucher}'s own
 * service NEVER performs those transitions, in Django or here. {@code order}'s future port
 * should autowire {@link AppliedVoucherRepository} directly and write those transitions itself,
 * exactly mirroring Django's own layering (this is not an oversight — grepped every
 * {@code AppliedVoucher.*status=(USED|CANCELLED)} write site in the whole backend tree and they
 * are 100% inside {@code order}).
 *
 * <h2>Forward seam for {@code order} — no Order/Checkout entity needed here</h2>
 * {@code order} isn't ported yet (comes right after {@code voucher} per CLAUDE.md §7's
 * recommended order), so the two reservation-apply methods below are deliberately
 * "primitive-ized" versions of Django's {@code apply_platform_voucher_reservation}/
 * {@code apply_shop_voucher_reservation} (which take live {@code Checkout}/{@code Order} model
 * instances): they take the checkout/order's {@code uid} plus whatever amounts Django would
 * have read off that object, instead of the object itself — the same pattern {@code cart} used
 * for the seam it left {@code order} ({@code CartService.getSelectedCartItemsByUser} etc.), and
 * {@code dish} used for {@code StockReservation.orderUid} (a plain column, not yet a real FK,
 * see this module's own {@code V9__init_voucher.sql}). Concretely: Django's
 * {@code _calculate_net_subtotal(checkout)} walks {@code checkout.order_fk_checkout.all()} and
 * needs each order's {@code sub_total} plus its own already-reserved SHOP_VOUCHER discount —
 * the discount half is 100% {@code voucher}'s own data ({@link AppliedVoucherRepository}), so
 * {@link #calculateNetSubtotal} takes a plain {@code List<OrderSubtotal>} (uid + sub_total per
 * order) instead of a {@code Checkout} entity — {@code order}'s future port supplies that list
 * from its own orders-under-a-checkout query, nothing else changes.
 *
 * <h2>Real Django gap preserved: voucher endpoints are NOT role-gated at all</h2>
 * {@code voucher/api.py} declares {@code auth=AuthBear()} with <b>zero</b>
 * {@code @require_group}/{@code @require_permission} decorators on any route — including
 * {@code create_voucher}, whose docstring says "(Chef only)" but which is enforced nowhere:
 * the service does {@code chef=request.user} unconditionally. <b>Any authenticated user,
 * including a plain CUSTOMER, can create a voucher under their own id</b> (it just never
 * matches a real chef's orders in practice, since {@code apply_shop_voucher_reservation} looks
 * the voucher up by {@code chef=order.chef}, and a CUSTOMER never owns an order as its chef).
 * Ported faithfully — {@link com.amomeal.marketplace.voucher.web.VoucherController} has no
 * {@code @PreAuthorize} on any endpoint, confirmed against {@code api.py} rather than assumed.
 * Flagged in PROGRESS.md, not fixed.
 */
@Service
@RequiredArgsConstructor
public class VoucherService {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);
    private static final Set<VoucherReservationStatus> ACTIVE_STATUSES =
            Set.of(VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED);

    private final VoucherRepository voucherRepository;
    private final AppliedVoucherRepository appliedVoucherRepository;

    // =====================================================================
    // CRUD (Django: create_voucher / get_voucher_by_uid / get_voucher_by_code /
    // get_my_vouchers / update_voucher / delete_voucher)
    // =====================================================================

    /**
     * Django: {@code VoucherService.create_voucher}. {@code voucher_type} is force-set to
     * {@code SHOP_VOUCHER} regardless of anything the caller might send — Django's
     * {@code VoucherORM.create_voucher} hardcodes it too, and {@code CreateVoucherSchema} has
     * no {@code voucher_type} field to send in the first place.
     */
    @Transactional
    public Voucher createVoucher(CustomUser chef, CreateVoucherRequest payload) {
        // Post-port fix (2026-09-25): Django's docstring says "Chef only" but never enforced it.
        if (!chef.isChef() && !chef.isAdmin()) {
            throw new VoucherNotOwnedException();
        }
        // Django: `check_code_exists` has no chef filter -- GLOBAL code uniqueness enforced at
        // the app layer, even though the DB's own unique_together is scoped per-chef (see
        // V9__init_voucher.sql). Preserved verbatim, not narrowed to "unique per chef".
        if (voucherRepository.existsByCodeIgnoreCase(payload.code())) {
            throw new VoucherCodeAlreadyExistsException();
        }
        if (!payload.startDate().isBefore(payload.endDate())) {
            throw new VoucherInvalidException("Ngày bắt đầu phải trước ngày kết thúc");
        }
        if (payload.discountType() == com.amomeal.marketplace.voucher.entity.VoucherDiscountType.PERCENTAGE) {
            BigDecimal dv = payload.discountValue();
            if (dv.compareTo(BigDecimal.ZERO) <= 0 || dv.compareTo(new BigDecimal("100")) > 0) {
                throw new VoucherInvalidException("Giá trị giảm giá phần trăm phải trong khoảng (0, 100]");
            }
        }
        Voucher voucher = Voucher.builder()
                .chef(chef)
                .code(payload.code().toUpperCase(Locale.ROOT))
                .name(payload.name())
                .description(payload.description())
                .voucherType(VoucherType.SHOP_VOUCHER)
                .discountType(payload.discountType())
                .discountValue(payload.discountValue())
                .maxDiscountAmount(payload.maxDiscountAmount())
                .minOrderAmount(payload.minOrderAmount() == null ? BigDecimal.ZERO : payload.minOrderAmount())
                .startDate(payload.startDate())
                .endDate(payload.endDate())
                .usageLimit(payload.usageLimit())
                .usageLimitPerUser(payload.usageLimitPerUser() == null ? 1 : payload.usageLimitPerUser())
                .isActive(payload.isActive() == null || payload.isActive())
                .build();
        return voucherRepository.save(voucher);
    }

    @Transactional(readOnly = true)
    public Voucher getVoucherByUid(UUID uid) {
        return voucherRepository.findById(uid).orElseThrow(VoucherNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Voucher getVoucherByCode(String code) {
        return voucherRepository.findFirstByCodeIgnoreCase(code).orElseThrow(VoucherNotFoundException::new);
    }

    /** Django: {@code VoucherService.get_my_vouchers} — ALL of the chef's vouchers, no
     * active/date-window filter (unlike {@link #getAvailableVouchersByChef}). */
    @Transactional(readOnly = true)
    public List<Voucher> getMyVouchers(CustomUser chef) {
        return voucherRepository.findByChefOrderByCreatedAtDesc(chef);
    }

    /** Django: {@code VoucherService.get_vouchers_by_chef}. */
    @Transactional(readOnly = true)
    public List<Voucher> getVouchersByChef(Long chefId, boolean availableOnly, CustomUser user) {
        if (availableOnly) {
            return getAvailableVouchersByChef(chefId, user);
        }
        return voucherRepository.findActiveByChef(chefId, Instant.now());
    }

    /**
     * Django: {@code VoucherService.get_available_vouchers_by_chef}. Filters the chef's active,
     * in-date-window vouchers down to ones that still have quota (total usage_limit) and, if a
     * user is supplied, per-user quota — using the SAME non-status-filtered
     * {@link AppliedVoucherRepository#countByVoucherAndUser} Django uses here (not the
     * RESERVED/USED-only count used by the reservation-apply flows — see that repository
     * method's javadoc for why these are deliberately different).
     */
    @Transactional(readOnly = true)
    public List<Voucher> getAvailableVouchersByChef(Long chefId, CustomUser user) {
        List<Voucher> vouchers = voucherRepository.findActiveByChef(chefId, Instant.now());
        List<Voucher> result = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            long activeCount = appliedVoucherRepository.countByVoucherAndStatusIn(voucher, ACTIVE_STATUSES);
            if (voucher.getUsageLimit() != null && activeCount >= voucher.getUsageLimit()) {
                continue;
            }
            if (user != null) {
                long userUsage = appliedVoucherRepository.countByVoucherAndUser(voucher, user);
                if (userUsage >= voucher.getUsageLimitPerUser()) {
                    continue;
                }
            }
            result.add(voucher);
        }
        return result;
    }

    /**
     * Django: {@code VoucherService.update_voucher}. Ownership check bypasses for "admin" —
     * see the class-wide javadoc on {@link CustomUser#isAdmin()}/{@link CustomUser#isStaff()}
     * usage below: this is the ONE place in the whole Django backend (besides
     * {@link #deleteVoucher}) where {@code is_staff} genuinely still matters for authorization,
     * as a secondary OR-fallback on top of the real ADMIN-group check
     * ({@code chef.groups.filter(name="ADMIN").exists() or chef.is_staff}) — every other module
     * in this port uses the ADMIN group alone. Ported exactly: a plain ADMIN-group member
     * succeeds, AND a CHEF (no ADMIN group) with {@code is_staff=true} also succeeds.
     *
     * <p>PORT-NOTE: no code-conflict check and no percentage-bounds re-validation here, both
     * structurally dead via the real HTTP API — {@code UpdateVoucherSchema} has neither a
     * {@code code} nor a {@code voucher_type} field, so Django's own
     * {@code if 'code' in update_data} branch never fires, and its percentage-bounds check
     * compares {@code voucher_type} (SHOP_VOUCHER/PLATFORM_*) against the literal string
     * "PERCENTAGE" — a field/value mismatch that can never be true even if voucher_type were
     * present. Net effect in Django (and here): {@code discount_value} can be PATCHed to any
     * value via this endpoint, including <= 0 or > 100 on a PERCENTAGE-discount voucher, with
     * no validation at all. Not fixed.
     */
    @Transactional
    public Voucher updateVoucher(UUID voucherUid, CustomUser chef, UpdateVoucherRequest payload) {
        Voucher voucher = getVoucherByUid(voucherUid);

        boolean isAdmin = chef.isAdmin() || chef.isStaff();
        if (!isAdmin && (voucher.getChef() == null || !voucher.getChef().getId().equals(chef.getId()))) {
            throw new VoucherNotOwnedException();
        }

        if (payload.name() != null) {
            voucher.setName(payload.name());
        }
        if (payload.description() != null) {
            voucher.setDescription(payload.description());
        }
        if (payload.discountValue() != null) {
            voucher.setDiscountValue(payload.discountValue());
        }
        if (payload.maxDiscountAmount() != null) {
            voucher.setMaxDiscountAmount(payload.maxDiscountAmount());
        }
        if (payload.minOrderAmount() != null) {
            voucher.setMinOrderAmount(payload.minOrderAmount());
        }
        if (payload.startDate() != null) {
            voucher.setStartDate(payload.startDate());
        }
        if (payload.endDate() != null) {
            voucher.setEndDate(payload.endDate());
        }
        if (payload.usageLimit() != null) {
            voucher.setUsageLimit(payload.usageLimit());
        }
        if (payload.usageLimitPerUser() != null) {
            voucher.setUsageLimitPerUser(payload.usageLimitPerUser());
        }
        if (payload.isActive() != null) {
            voucher.setActive(payload.isActive());
        }
        return voucherRepository.save(voucher);
    }

    /** Django: {@code VoucherService.delete_voucher} — a real hard delete (Voucher has no
     * soft-delete field), same ADMIN-or-{@code is_staff} ownership bypass as {@link #updateVoucher}. */
    @Transactional
    public void deleteVoucher(UUID voucherUid, CustomUser chef) {
        Voucher voucher = getVoucherByUid(voucherUid);
        boolean isAdmin = chef.isAdmin() || chef.isStaff();
        if (!isAdmin && (voucher.getChef() == null || !voucher.getChef().getId().equals(chef.getId()))) {
            throw new VoucherNotOwnedException();
        }
        voucherRepository.delete(voucher);
    }

    /** {@code resolve_usage_count} — RESERVED+USED count for a single voucher, used to build
     * {@code VoucherDetailResponse}. */
    @Transactional(readOnly = true)
    public long usageCount(Voucher voucher) {
        return appliedVoucherRepository.countByVoucherAndStatusIn(voucher, ACTIVE_STATUSES);
    }

    // =====================================================================
    // Order-time validation (Django: validate_voucher_for_order)
    // =====================================================================

    /**
     * Django: {@code VoucherService.validate_voucher_for_order}. Precedence ported exactly:
     * not-found -> chef mismatch -> inactive -> before start -> after end -> total usage limit
     * -> per-user usage limit (using the NON-status-filtered count, matching
     * {@link #getAvailableVouchersByChef}, not the reservation flows' status-filtered count)
     * -> min order amount -> compute discount. Every "invalid" branch returns
     * {@code discountAmount = BigDecimal.ZERO} (Django returns {@code Decimal("0")}, not
     * {@code None}) — it's {@code VoucherController.validate_voucher} that nulls it out for the
     * HTTP response when {@code is_valid} is false, not this method.
     */
    @Transactional(readOnly = true)
    public VoucherValidationResult validateVoucherForOrder(String code, BigDecimal orderAmount, Long chefId, CustomUser user) {
        Optional<Voucher> maybeVoucher = voucherRepository.findFirstByCodeIgnoreCase(code);
        if (maybeVoucher.isEmpty()) {
            return invalid("Mã voucher không tồn tại");
        }
        Voucher voucher = maybeVoucher.get();

        if (voucher.getChef() == null || !voucher.getChef().getId().equals(chefId)) {
            return invalid("Voucher này không áp dụng cho chef của đơn hàng");
        }
        if (!voucher.isActive()) {
            return invalid("Voucher không còn hoạt động");
        }
        Instant now = Instant.now();
        if (now.isBefore(voucher.getStartDate())) {
            return invalid("Voucher chưa đến ngày hiệu lực");
        }
        if (now.isAfter(voucher.getEndDate())) {
            return invalid("Voucher đã hết hạn");
        }
        long activeCount = appliedVoucherRepository.countByVoucherAndStatusIn(voucher, ACTIVE_STATUSES);
        if (voucher.getUsageLimit() != null && activeCount >= voucher.getUsageLimit()) {
            return invalid("Voucher đã hết lượt sử dụng");
        }
        if (user != null) {
            long userUsage = appliedVoucherRepository.countByVoucherAndUser(voucher, user);
            if (userUsage >= voucher.getUsageLimitPerUser()) {
                return invalid("Bạn đã sử dụng hết lượt cho voucher này");
            }
        }
        if (orderAmount.compareTo(voucher.getMinOrderAmount()) < 0) {
            return invalid(String.format(Locale.US, "Đơn hàng tối thiểu phải từ %,.0fđ", voucher.getMinOrderAmount()));
        }
        BigDecimal discount = voucher.calculateDiscount(orderAmount);
        return new VoucherValidationResult(true, "Voucher hợp lệ", discount);
    }

    private static VoucherValidationResult invalid(String message) {
        return new VoucherValidationResult(false, message, BigDecimal.ZERO);
    }

    // =====================================================================
    // Reservation lifecycle (Django: apply_platform_voucher_reservation /
    // apply_shop_voucher_reservation / _calculate_net_subtotal)
    // =====================================================================

    /**
     * Django: {@code VoucherService._calculate_net_subtotal(checkout)}. Fully self-contained
     * in {@code voucher}'s own data — see this class's "forward seam" javadoc for why it takes
     * a plain list instead of a {@code Checkout} entity.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateNetSubtotal(List<OrderSubtotal> orders) {
        if (orders.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<UUID> orderUids = orders.stream().map(OrderSubtotal::orderUid).toList();
        Map<UUID, BigDecimal> shopDiscounts = appliedVoucherRepository
                .sumShopDiscountByOrderUidIn(orderUids, ACTIVE_STATUSES).stream()
                .collect(Collectors.toMap(OrderDiscountTotal::getOrderUid, OrderDiscountTotal::getTotal));

        BigDecimal netSubtotal = BigDecimal.ZERO;
        for (OrderSubtotal order : orders) {
            BigDecimal shopDiscount = shopDiscounts.getOrDefault(order.orderUid(), BigDecimal.ZERO);
            BigDecimal net = order.subTotal().subtract(shopDiscount);
            netSubtotal = netSubtotal.add(net.max(BigDecimal.ZERO));
        }
        return netSubtotal;
    }

    /**
     * Django: {@code VoucherService.apply_platform_voucher_reservation(user, checkout,
     * voucher_code, voucher_type)}. See this class's "forward seam" javadoc for why
     * {@code checkoutUid}/{@code snapshot} replace a live {@code Checkout} instance.
     *
     * <p>Concurrency: {@code Voucher.objects.select_for_update()} -> {@code @Lock(PESSIMISTIC_WRITE)}
     * in {@link VoucherRepository#findForUpdateByCodeAndVoucherType} — the row lock on
     * {@code voucher} is what makes the total-usage-limit check-then-insert atomic under
     * concurrent callers (proven in {@code VoucherReservationServiceTest}'s multi-thread test,
     * mirroring {@code dish}'s {@code StockReservationServiceTest}).
     */
    @Transactional
    public AppliedVoucher applyPlatformVoucherReservation(CustomUser user, UUID checkoutUid, String voucherCode,
                                                           VoucherType voucherType, PlatformCheckoutSnapshot snapshot) {
        if (voucherType != VoucherType.PLATFORM_SUBTOTAL && voucherType != VoucherType.PLATFORM_SHIPPING) {
            throw new VoucherInvalidException(
                    "Chỉ platform vouchers (PLATFORM_SUBTOTAL, PLATFORM_SHIPPING) mới có thể apply vào checkout");
        }

        Voucher voucher = voucherRepository.findForUpdateByCodeAndVoucherType(voucherCode.toUpperCase(Locale.ROOT), voucherType)
                .orElseThrow(() -> new VoucherNotFoundException(
                        "Voucher không tồn tại hoặc không phải loại " + voucherType));

        appliedVoucherRepository.expireOldReservations(voucher, Instant.now());

        Optional<AppliedVoucher> existing = appliedVoucherRepository
                .findFirstByVoucherAndCheckoutUidAndUserAndStatus(voucher, checkoutUid, user, VoucherReservationStatus.RESERVED);

        if (existing.isPresent()) {
            AppliedVoucher reservation = existing.get();
            BigDecimal discount = computePlatformDiscount(voucher, voucherType, snapshot);
            reservation.setDiscountAmount(discount);
            reservation.setCheckoutUid(checkoutUid);
            reservation.setReservationExpiresAt(Instant.now().plus(RESERVATION_TTL));
            return appliedVoucherRepository.save(reservation);
        }

        long activeCount = appliedVoucherRepository.countByVoucherAndStatusIn(voucher, ACTIVE_STATUSES);
        if (voucher.getUsageLimit() != null && activeCount >= voucher.getUsageLimit()) {
            throw new VoucherInvalidException("Voucher đã hết lượt sử dụng");
        }

        Voucher.Validity validity = voucher.checkValidity(Instant.now());
        if (!validity.valid()) {
            throw new VoucherInvalidException(validity.message());
        }

        long userCount = appliedVoucherRepository.countByVoucherAndUserAndStatusIn(voucher, user, ACTIVE_STATUSES);
        if (userCount >= voucher.getUsageLimitPerUser()) {
            throw new VoucherInvalidException(
                    "Bạn đã sử dụng voucher này " + userCount + " lần (giới hạn: " + voucher.getUsageLimitPerUser() + ")");
        }

        BigDecimal discount = computePlatformDiscount(voucher, voucherType, snapshot);

        AppliedVoucher reservation = AppliedVoucher.builder()
                .voucher(voucher)
                .checkoutUid(checkoutUid)
                .user(user)
                .voucherType(voucherType)
                .discountAmount(discount)
                .status(VoucherReservationStatus.RESERVED)
                .reservationExpiresAt(Instant.now().plus(RESERVATION_TTL))
                .build();
        return appliedVoucherRepository.save(reservation);
    }

    private BigDecimal computePlatformDiscount(Voucher voucher, VoucherType voucherType, PlatformCheckoutSnapshot snapshot) {
        if (voucherType == VoucherType.PLATFORM_SUBTOTAL) {
            return voucher.calculateDiscount(snapshot.netSubtotal());
        }
        return voucher.calculateShippingDiscount(snapshot.deliveryFee(), snapshot.checkoutSubtotal());
    }

    /**
     * Django: {@code VoucherService.apply_shop_voucher_reservation(user, order, voucher_code)}.
     * {@code checkoutUidHint} is an addition (no Django parameter) so the future {@code order}
     * port can still denormalize the owning checkout's uid onto this row the same way Django's
     * {@code VoucherORM.create_voucher_reservation} auto-derives {@code checkout =
     * order.checkout} when only {@code order} is given — pass {@code null} if unknown/not
     * applicable yet.
     */
    @Transactional
    public AppliedVoucher applyShopVoucherReservation(CustomUser user, UUID orderUid, UUID checkoutUidHint,
                                                       CustomUser orderChef, BigDecimal orderSubTotal, String voucherCode) {
        Voucher voucher = voucherRepository.findShopVoucherForUpdate(voucherCode.toUpperCase(Locale.ROOT), orderChef)
                .orElseThrow(() -> new VoucherNotFoundException(
                        "Voucher không tồn tại hoặc không phải SHOP_VOUCHER của chef này" + voucherCode));

        appliedVoucherRepository.expireOldReservations(voucher, Instant.now());

        Optional<AppliedVoucher> existing = appliedVoucherRepository
                .findFirstByVoucherAndOrderUidAndUserAndStatus(voucher, orderUid, user, VoucherReservationStatus.RESERVED);

        if (existing.isPresent()) {
            AppliedVoucher reservation = existing.get();
            BigDecimal discount = voucher.calculateDiscount(orderSubTotal);
            reservation.setDiscountAmount(discount);
            reservation.setOrderUid(orderUid);
            if (checkoutUidHint != null) {
                reservation.setCheckoutUid(checkoutUidHint);
            }
            reservation.setReservationExpiresAt(Instant.now().plus(RESERVATION_TTL));
            return appliedVoucherRepository.save(reservation);
        }

        long activeCount = appliedVoucherRepository.countByVoucherAndStatusIn(voucher, ACTIVE_STATUSES);
        if (voucher.getUsageLimit() != null && activeCount >= voucher.getUsageLimit()) {
            throw new VoucherInvalidException("Voucher đã hết lượt sử dụng");
        }

        Voucher.Validity validity = voucher.checkValidity(Instant.now());
        if (!validity.valid()) {
            throw new VoucherInvalidException(validity.message());
        }

        long userCount = appliedVoucherRepository.countByVoucherAndUserAndStatusIn(voucher, user, ACTIVE_STATUSES);
        if (userCount >= voucher.getUsageLimitPerUser()) {
            throw new VoucherInvalidException(
                    "Bạn đã sử dụng voucher này " + userCount + " lần (giới hạn: " + voucher.getUsageLimitPerUser() + ")");
        }

        BigDecimal discount = voucher.calculateDiscount(orderSubTotal);

        AppliedVoucher reservation = AppliedVoucher.builder()
                .voucher(voucher)
                .orderUid(orderUid)
                .checkoutUid(checkoutUidHint)
                .user(user)
                .voucherType(VoucherType.SHOP_VOUCHER)
                .discountAmount(discount)
                .status(VoucherReservationStatus.RESERVED)
                .reservationExpiresAt(Instant.now().plus(RESERVATION_TTL))
                .build();
        return appliedVoucherRepository.save(reservation);
    }
}
