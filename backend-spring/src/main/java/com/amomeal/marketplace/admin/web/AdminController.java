package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.admin.dto.AdminDtos.*;
import com.amomeal.marketplace.admin.exception.AdminValidationException;
import com.amomeal.marketplace.admin.service.AdminService;
import com.amomeal.marketplace.admin.util.AdminParams;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Mirrors ../../backend/admin/api.py::AdminController (prefix {@code /api/admin}). Every route requires the
 * ADMIN group; a non-admin gets Django's 403 PERMISSION_DENIED (see {@link AdminService#requireAdmin}) and a
 * missing/invalid token the standard 401.
 *
 * <p><b>Order of failures matches ninja</b>: request validation (query params / body) is 401
 * VALIDATION_ERROR and happens BEFORE the admin check, so every query parameter is taken as a raw string
 * and parsed here first; only then is {@link AdminService#requireAdmin} called, then the work. Endpoints that
 * live in other modules' controllers (reports under {@code /api/admin/reports}; certificate list/status under
 * {@code /api/certificates}; voucher update/delete under {@code /api/vouchers}) are NOT duplicated here.
 *
 * <p>Note {@code /api/admin/voucher/{uid}} and {@code /api/admin/voucher/{uid}/status} appear in FE-admin's
 * constants but have no Django route, and FE-admin's services never call them.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService service;

    private static long id(String raw, String field) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new AdminValidationException(field, "Input should be a valid integer, unable to parse string as an integer");
        }
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/users")
    public PageResponse<UserListItem> getUsers(@AuthenticationPrincipal CustomUser user,
                                               @RequestParam(name = "user_type", required = false) String userType,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(name = "is_active", required = false) String isActive,
                                               @RequestParam(name = "from_date", required = false) String fromDate,
                                               @RequestParam(name = "to_date", required = false) String toDate,
                                               @RequestParam(required = false) String page,
                                               @RequestParam(name = "page_size", required = false) String pageSize) {
        Boolean active = AdminParams.boolParam(isActive, "is_active");
        int p = AdminParams.intParam(page, "page", 1);
        int size = AdminParams.intParam(pageSize, "page_size", 50);
        service.requireAdmin(user);
        return service.getUsers(userType, search, active, fromDate, toDate, p, size);
    }

    @PatchMapping("/users/{userId}/deactivate")
    public boolean deactivateUser(@AuthenticationPrincipal CustomUser user, @PathVariable String userId) {
        long uid = id(userId, "user_id");
        service.requireAdmin(user);
        return service.setUserActive(uid, false);
    }

    @PatchMapping("/users/{userId}/activate")
    public boolean activateUser(@AuthenticationPrincipal CustomUser user, @PathVariable String userId) {
        long uid = id(userId, "user_id");
        service.requireAdmin(user);
        return service.setUserActive(uid, true);
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/dashboard/overview")
    public DashboardOverview overview(@AuthenticationPrincipal CustomUser user) {
        service.requireAdmin(user);
        return service.overview();
    }

    @GetMapping("/dashboard/revenue-chart")
    public RevenueChartResponse revenueChart(@AuthenticationPrincipal CustomUser user,
                                             @RequestParam(name = "from_date", required = false) String fromDate,
                                             @RequestParam(name = "to_date", required = false) String toDate) {
        AdminParams.required(fromDate, "from_date");
        AdminParams.required(toDate, "to_date");
        service.requireAdmin(user);
        // Django parses with strptime: a malformed date is an uncaught ValueError (500), not a validation error
        return service.revenueChart(AdminParams.strptimeDate(fromDate), AdminParams.strptimeDate(toDate));
    }

    @GetMapping("/dashboard/payment-methods")
    public PaymentMethodStatsResponse paymentMethods(@AuthenticationPrincipal CustomUser user) {
        service.requireAdmin(user);
        return service.paymentMethodStats();
    }

    @GetMapping("/dashboard/order-status")
    public OrderStatusStatsResponse orderStatus(@AuthenticationPrincipal CustomUser user) {
        service.requireAdmin(user);
        return service.orderStatusStats();
    }

    @GetMapping("/dashboard/success-orders-by-district")
    public DistrictStatsResponse successOrdersByDistrict(@AuthenticationPrincipal CustomUser user,
                                                         @RequestParam(name = "from_date", required = false) String fromDate,
                                                         @RequestParam(name = "to_date", required = false) String toDate) {
        LocalDate from = AdminParams.dateParam(fromDate, "from_date");
        LocalDate to = AdminParams.dateParam(toDate, "to_date");
        service.requireAdmin(user);
        return service.successOrdersByDistrict(from, to);
    }

    @GetMapping("/dashboard/top-chefs")
    public List<TopChefItem> topChefs(@AuthenticationPrincipal CustomUser user,
                                      @RequestParam(required = false) String limit) {
        int n = AdminParams.intParam(limit, "limit", 5);
        service.requireAdmin(user);
        return service.topChefs(Math.min(n, 20));
    }

    // ------------------------------------------------------------------ orders

    @GetMapping("/orders")
    public PageResponse<OrderListItem> getOrders(@AuthenticationPrincipal CustomUser user,
                                                 @RequestParam(name = "customer_email", required = false) String customerEmail,
                                                 @RequestParam(name = "chef_email", required = false) String chefEmail,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(name = "payment_status", required = false) String paymentStatus,
                                                 @RequestParam(name = "payment_method", required = false) String paymentMethod,
                                                 @RequestParam(name = "from_date", required = false) String fromDate,
                                                 @RequestParam(name = "to_date", required = false) String toDate,
                                                 @RequestParam(required = false) String page,
                                                 @RequestParam(name = "page_size", required = false) String pageSize) {
        int p = AdminParams.intParam(page, "page", 1);
        int size = AdminParams.intParam(pageSize, "page_size", 50);
        service.requireAdmin(user);
        return service.getOrders(customerEmail, chefEmail, status, paymentStatus, paymentMethod, fromDate, toDate, p, size);
    }

    @GetMapping("/orders/{orderUid}")
    public OrderDetail getOrderDetail(@AuthenticationPrincipal CustomUser user, @PathVariable String orderUid) {
        service.requireAdmin(user);
        return service.getOrderDetail(orderUid);
    }

    // ------------------------------------------------------------------ vouchers

    @PostMapping("/voucher")
    public AdminVoucherResponse createVoucher(@AuthenticationPrincipal CustomUser user,
                                              @Valid @RequestBody AdminCreateVoucherRequest payload) {
        Instant start = AdminService.parseDateTime(payload.startDate(), "start_date");
        Instant end = AdminService.parseDateTime(payload.endDate(), "end_date");
        return service.createVoucher(user, payload, start, end);
    }

    @GetMapping("/voucher")
    public List<AdminVoucherResponse> listVouchers(@AuthenticationPrincipal CustomUser user) {
        return service.listVouchers(user);
    }

    // ------------------------------------------------------------------ KYC / certificates

    @GetMapping("/verification/{userId}")
    public VerificationReview verificationReview(@AuthenticationPrincipal CustomUser user, @PathVariable String userId) {
        long uid = id(userId, "user_id");
        service.requireAdmin(user);
        return service.getVerificationReview(uid);
    }

    @PatchMapping("/certificate/{certificateUid}")
    public boolean setCertificateStatus(@AuthenticationPrincipal CustomUser user, @PathVariable String certificateUid,
                                        @RequestParam(required = false) String status) {
        AdminParams.required(status, "status");
        CertificateStatus parsed;
        try {
            parsed = CertificateStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new AdminValidationException("status", "Input should be 'PENDING', 'ACTIVE', 'EXPIRED' or 'REVOKED'");
        }
        service.requireAdmin(user);
        return service.setCertificateStatus(user, certificateUid, parsed);
    }

    // ------------------------------------------------------------------ bank accounts

    @PatchMapping("/bank-accounts/customers/{bankAccountId}/verification")
    public CustomerBankAccount verifyCustomerBank(@AuthenticationPrincipal CustomUser user,
                                                  @PathVariable String bankAccountId,
                                                  @Valid @RequestBody VerifyBankAccountRequest payload) {
        long bid = id(bankAccountId, "bank_account_id");
        service.requireAdmin(user);
        return AdminService.asCustomerSchema(service.verifyBankAccount(bid, payload.status()));
    }

    @PatchMapping("/bank-accounts/chefs/{bankAccountId}/verification")
    public ChefBankAccount verifyChefBank(@AuthenticationPrincipal CustomUser user,
                                          @PathVariable String bankAccountId,
                                          @Valid @RequestBody VerifyBankAccountRequest payload) {
        long bid = id(bankAccountId, "bank_account_id");
        service.requireAdmin(user);
        return AdminService.asChefSchema(service.verifyBankAccount(bid, payload.status()));
    }

    @GetMapping("/bank-accounts/chefs")
    public PageResponse<ChefBankAccount> chefBankAccounts(@AuthenticationPrincipal CustomUser user,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String search,
                                                          @RequestParam(required = false) String page,
                                                          @RequestParam(name = "page_size", required = false) String pageSize) {
        Boolean st = AdminParams.boolParam(status, "status");
        int p = AdminParams.intParam(page, "page", 1);
        int size = AdminParams.intParam(pageSize, "page_size", 50);
        service.requireAdmin(user);
        return service.getChefBankAccounts(st, search, p, size);
    }

    @GetMapping("/bank-accounts/customers")
    public PageResponse<CustomerBankAccount> customerBankAccounts(@AuthenticationPrincipal CustomUser user,
                                                                  @RequestParam(required = false) String status,
                                                                  @RequestParam(required = false) String search,
                                                                  @RequestParam(required = false) String page,
                                                                  @RequestParam(name = "page_size", required = false) String pageSize) {
        Boolean st = AdminParams.boolParam(status, "status");
        int p = AdminParams.intParam(page, "page", 1);
        int size = AdminParams.intParam(pageSize, "page_size", 50);
        service.requireAdmin(user);
        return service.getCustomerBankAccounts(st, search, p, size);
    }
}
