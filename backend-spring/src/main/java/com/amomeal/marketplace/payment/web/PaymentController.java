package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.payment.dto.CancelPaymentRequest;
import com.amomeal.marketplace.payment.dto.CancelPaymentResponse;
import com.amomeal.marketplace.payment.dto.ChefBalanceSummaryResponse;
import com.amomeal.marketplace.payment.dto.CodSettlementResponse;
import com.amomeal.marketplace.payment.dto.CreatePaymentRequest;
import com.amomeal.marketplace.payment.dto.CreatePaymentResponse;
import com.amomeal.marketplace.payment.dto.CustomerPaymentInfoRequest;
import com.amomeal.marketplace.payment.dto.CustomerPaymentInfoResponse;
import com.amomeal.marketplace.payment.dto.InternalWalletSummaryResponse;
import com.amomeal.marketplace.payment.dto.PaymentData;
import com.amomeal.marketplace.payment.dto.PaymentInfoResponse;
import com.amomeal.marketplace.payment.dto.PaymentInvoiceInfo;
import com.amomeal.marketplace.payment.dto.PaymentInvoicesResponse;
import com.amomeal.marketplace.payment.dto.PaymentOtpSessionResponse;
import com.amomeal.marketplace.payment.dto.PaymentOtpVerifyRequest;
import com.amomeal.marketplace.payment.dto.PaymentStatusResponse;
import com.amomeal.marketplace.payment.dto.WalletWithdrawRequest;
import com.amomeal.marketplace.payment.dto.WalletWithdrawResponse;
import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.exception.PaymentHttpException;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.service.BankInfoService;
import com.amomeal.marketplace.payment.service.PaymentService;
import com.amomeal.marketplace.payment.service.PaymentStateService;
import com.amomeal.marketplace.payment.service.PaymentTx;
import com.amomeal.marketplace.payment.service.PaymentValueError;
import com.amomeal.marketplace.payment.service.SettlementService;
import com.amomeal.marketplace.payment.service.WalletService;
import com.amomeal.marketplace.payment.service.WithdrawalService;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of ../../backend/payment/api.py::PaymentController ({@code @api(prefix_or_class="payment")},
 * every route {@code auth=AuthBear()}, no group/permission decorator — the
 * {@code @require_group(CUSTOMER)} lines on the bank-info routes are commented out in Django).
 * Normal endpoints: they go through the standard response envelope.
 *
 * <p>Post-port authorization fix (2026-09-25; Django had none): create/cancel need the checkout's
 * owner, status/invoices/info the owner or ADMIN, chef balance / COD settlement that chef or ADMIN.
 * Service-thrown 403 {@link PaymentHttpException} (HTTP_ERROR style), not {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentStateService stateService;
    private final WalletService walletService;
    private final WithdrawalService withdrawalService;
    private final BankInfoService bankInfoService;
    private final SettlementService settlementService;
    private final CustomUserRepository userRepository;
    private final PaymentTx tx;

    /**
     * Django {@code create_payment}: ValueError → {@code success=false, message=str(e)}; any
     * other exception → {@code "Internal error: ..."} — both still HTTP 200.
     */
    @PostMapping("/create")
    public CreatePaymentResponse createPayment(@AuthenticationPrincipal CustomUser user,
                                               @Valid @RequestBody CreatePaymentRequest payload) {
        paymentService.assertCheckoutAccess(payload.checkoutUid(), user, false);
        try {
            PaymentTransaction payment = paymentService.createPayment(payload.checkoutUid(), payload.paymentMethod(),
                    payload.bankCode(), payload.language() != null && !payload.language().isEmpty() ? payload.language() : "vn");
            PaymentTransactionState state = stateService.findState(payment).orElse(null);
            Map<String, Object> gw = state != null ? state.getGatewayResponse() : null;
            boolean hasGw = gw != null && !gw.isEmpty();
            String transactionId = state == null ? null
                    : state.getTransactionId() != null && !state.getTransactionId().isEmpty() ? state.getTransactionId()
                    : state.getPayosPaymentLinkId();
            PaymentData data = new PaymentData(payment.getUid(), payment.getCheckoutUid(), payment.getPaymentMethod().name(),
                    payment.getAmount(), state != null ? state.getStatus().name() : "PENDING",
                    state != null ? state.getPaymentUrl() : null,
                    hasGw ? strOrNull(gw.get("qr_code")) : null,
                    state != null ? state.getPayosPaymentLinkId() : null,
                    payment.getPayosOrderCode(),
                    hasGw ? strOrNull(gw.get("account_number")) : null,
                    hasGw ? strOrNull(gw.get("account_name")) : null,
                    transactionId,
                    hasGw ? mapOrNull(gw.get("settlement")) : null);
            return new CreatePaymentResponse(true, "Payment created successfully", data);
        } catch (PaymentValueError ex) {
            return new CreatePaymentResponse(false, ex.getMessage(), null);
        } catch (RuntimeException ex) {
            return new CreatePaymentResponse(false, "Internal error: " + ex.getMessage(), null);
        }
    }

    /** Django {@code get_payment_status} (syncs a PENDING PayOS payment with PayOS first). Unknown uid → 500. */
    @GetMapping("/{paymentUid}/status")
    public PaymentStatusResponse getPaymentStatus(@AuthenticationPrincipal CustomUser user, @PathVariable UUID paymentUid) {
        paymentService.assertPaymentAccess(paymentUid, user, true);
        PaymentTransaction payment = paymentService.getPaymentStatus(paymentUid, true);
        PaymentTransactionState state = stateService.findState(payment).orElse(null);
        String transactionId = state != null && state.getTransactionId() != null && !state.getTransactionId().isEmpty()
                ? state.getTransactionId()
                : state != null && state.getPayosPaymentLinkId() != null && !state.getPayosPaymentLinkId().isEmpty()
                ? state.getPayosPaymentLinkId() : payment.getUid().toString();
        Map<String, Object> gw = state != null ? state.getGatewayResponse() : null;
        return new PaymentStatusResponse(payment.getUid(), transactionId,
                state != null ? state.getStatus().name() : "PENDING", payment.getAmount(),
                payment.getPaymentMethod().name(), payment.getCreatedAt(), state != null ? state.getPaidAt() : null,
                gw != null && !gw.isEmpty() ? mapOrNull(gw.get("settlement")) : null);
    }

    /** Django {@code get_payment_info} — PayOS passthrough by order code. */
    @GetMapping("/order/{orderCode}/info")
    public PaymentInfoResponse getPaymentInfo(@AuthenticationPrincipal CustomUser user, @PathVariable long orderCode) {
        paymentService.assertOrderCodeAccess(orderCode, user, true);
        Map<String, Object> r = paymentService.getPaymentInfoByOrderCode(orderCode);
        return new PaymentInfoResponse(PyCompat.truthy(r.get("success")), strOrNull(r.get("payment_link_id")),
                longOrNull(r.get("order_code")), longOrNull(r.get("amount")),
                r.containsKey("amount_paid") ? longOrNull(r.get("amount_paid")) : Long.valueOf(0),
                longOrNull(r.get("amount_remaining")), strOrNull(r.get("status")));
    }

    /** Django {@code cancel_payment} — see {@link PaymentService#cancelPayment} for the preserved inverted guard. */
    @PostMapping("/{paymentUid}/cancel")
    public CancelPaymentResponse cancelPayment(@AuthenticationPrincipal CustomUser user, @PathVariable UUID paymentUid,
                                         @RequestBody CancelPaymentRequest payload) {
        paymentService.assertPaymentAccess(paymentUid, user, false);
        Map<String, Object> r = paymentService.cancelPayment(paymentUid, payload.cancellationReason());
        return new CancelPaymentResponse(PyCompat.truthy(r.get("success")), strOrNull(r.get("status")),
                strOrNull(r.get("cancelled_at")), strOrNull(r.get("cancellation_reason")), strOrNull(r.get("error")));
    }

    /** Django {@code get_payment_invoices}. */
    @GetMapping("/{paymentUid}/invoices")
    public PaymentInvoicesResponse getPaymentInvoices(@AuthenticationPrincipal CustomUser user, @PathVariable UUID paymentUid) {
        paymentService.assertPaymentAccess(paymentUid, user, true);
        Map<String, Object> r = paymentService.getPaymentInvoices(paymentUid);
        List<PaymentInvoiceInfo> invoices = new ArrayList<>();
        if (r.get("invoices") instanceof List<?> raw) {
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new IllegalStateException("invalid invoice payload"); // pydantic ValidationError -> 500
                }
                invoices.add(new PaymentInvoiceInfo(required(m, "invoiceId"), required(m, "invoiceNumber"),
                        Long.valueOf(required(m, "issuedTimestamp")), required(m, "issuedDatetime"),
                        required(m, "transactionId"), required(m, "reservationCode"), required(m, "codeOfTax")));
            }
        }
        return new PaymentInvoicesResponse(PyCompat.truthy(r.get("success")), invoices, strOrNull(r.get("error")));
    }

    /** Django {@code upsert_customer_bank_info}: save unverified + email a 2-minute OTP. */
    @PostMapping("/customer/bank-info")
    public PaymentOtpSessionResponse upsertCustomerBankInfo(@AuthenticationPrincipal CustomUser user,
                                                            @Valid @RequestBody CustomerPaymentInfoRequest payload) {
        String token = bankInfoService.requestBankVerifyOtp(user, payload);
        return new PaymentOtpSessionResponse(token, "OTP sent to your email. Please verify to activate bank account.");
    }

    @PostMapping("/customer/bank-info/verify-otp")
    public CustomerPaymentInfoResponse verifyBankInfoOtp(@AuthenticationPrincipal CustomUser user,
                                                         @Valid @RequestBody PaymentOtpVerifyRequest payload) {
        return toResponse(bankInfoService.verifyBankInfoOtp(user, payload.resetSessionToken(), payload.otp()));
    }

    /** Django {@code get_customer_bank_info} — no row → AttributeError on None → 500 (preserved). */
    @GetMapping("/customer/bank-info")
    public CustomerPaymentInfoResponse getCustomerBankInfo(@AuthenticationPrincipal CustomUser user) {
        return toResponse(bankInfoService.getCustomerPaymentInfo(user)
                .orElseThrow(() -> new IllegalStateException("'NoneType' object has no attribute 'bank_name'")));
    }

    @GetMapping("/wallet/me")
    public InternalWalletSummaryResponse getMyWallet(@AuthenticationPrincipal CustomUser user) {
        return walletService.getInternalWalletSummary(user.getId());
    }

    @PostMapping("/wallet/me/withdraw")
    public PaymentOtpSessionResponse withdrawMyWallet(@AuthenticationPrincipal CustomUser user,
                                                      @Valid @RequestBody WalletWithdrawRequest payload) {
        String token = withdrawalService.requestWithdrawOtp(user, payload.amount());
        return new PaymentOtpSessionResponse(token, "OTP sent to your email. Please confirm the withdrawal.");
    }

    @PostMapping("/wallet/me/withdraw/confirm")
    public WalletWithdrawResponse confirmWithdraw(@AuthenticationPrincipal CustomUser user,
                                                  @Valid @RequestBody PaymentOtpVerifyRequest payload) {
        Map<String, Object> r = withdrawalService.confirmWithdrawWithOtp(user, payload.resetSessionToken(), payload.otp());
        Object message = r.containsKey("message") ? r.get("message") : r.getOrDefault("error", "");
        Object amount = r.get("amount");
        return new WalletWithdrawResponse(PyCompat.truthy(r.get("success")), PyCompat.str(message),
                amount instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null,
                r.containsKey("status") ? strOrNull(r.get("status")) : "FAILED",
                strOrNull(r.get("payout_id")), strOrNull(r.get("reference_id")), strOrNull(r.get("bank_account")),
                strOrNull(r.get("error")));
    }

    /** Django {@code get_chef_balance}: 404 unless the id belongs to a CHEF-group user. */
    @GetMapping("/chef/{chefId}/balance")
    public ChefBalanceSummaryResponse getChefBalance(@AuthenticationPrincipal CustomUser user, @PathVariable Long chefId) {
        requireSelfOrAdmin(user, chefId);
        CustomUser chef = requireChef(chefId);
        SettlementService.ChefBalanceSummary s = settlementService.getChefBalanceSummary(chef);
        return new ChefBalanceSummaryResponse(s.chefId(), s.chefEmail(),
                new ChefBalanceSummaryResponse.CodBalance(s.codUnsettledBalance(), s.codUnsettledOrders(),
                        "COD money collected from customers"),
                new ChefBalanceSummaryResponse.PayosBalance(s.walletBalance(), "Internal wallet available balance"),
                s.totalAvailablePayout(), s.totalSettled(), "VND");
    }

    /** Django {@code settle_chef_cod}. */
    @PostMapping("/chef/{chefId}/cod/settle")
    public CodSettlementResponse settleChefCod(@AuthenticationPrincipal CustomUser user, @PathVariable Long chefId) {
        requireSelfOrAdmin(user, chefId);
        CustomUser chef = requireChef(chefId);
        Map<String, Object> r = settlementService.settleCodBalanceForChef(chef);
        if (!PyCompat.truthy(r.get("success"))) {
            return new CodSettlementResponse(false, chef.getId(), chef.getEmail(), BigDecimal.ZERO, 0,
                    PyCompat.isoformat(Instant.now()), strOrNull(r.get("error")), null, List.of(), null);
        }
        return new CodSettlementResponse(true, ((Number) r.get("chef_id")).longValue(), strOrNull(r.get("chef_email")),
                BigDecimal.valueOf(((Number) r.get("settled_amount")).doubleValue()),
                ((Number) r.get("order_count")).intValue(), strOrNull(r.get("settled_at")), null, null, List.of(), null);
    }

    /** Post-port fix: a chef's balance / COD settlement is that chef's own (or ADMIN's). */
    private static void requireSelfOrAdmin(CustomUser user, Long chefId) {
        if (!user.isAdmin() && !chefId.equals(user.getId())) {
            throw new PaymentHttpException(HttpStatus.FORBIDDEN, "You don't have permission to access this chef's balance");
        }
    }

    private CustomUser requireChef(Long chefId) {
        return tx.required(() -> userRepository.findById(chefId).filter(u -> u.hasRole(UserRole.CHEF)))
                .orElseThrow(() -> new PaymentHttpException(HttpStatus.NOT_FOUND, "Chef not found or not a chef"));
    }

    private static CustomerPaymentInfoResponse toResponse(CustomerPaymentInfo info) {
        return new CustomerPaymentInfoResponse(info.getBankName(), info.getBankCode(), info.getBankAccountNumber(),
                info.getBankAccountName(), info.getBankBranch(), info.isVerified(),
                PyCompat.isoformat(info.getVerifiedAt()), PyCompat.isoformat(info.getCreatedAt()),
                PyCompat.isoformat(info.getUpdatedAt()));
    }

    private static String strOrNull(Object value) {
        return value == null ? null : PyCompat.str(value);
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String required(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalStateException("invoice field required: " + key);
        }
        return String.valueOf(v);
    }
}
