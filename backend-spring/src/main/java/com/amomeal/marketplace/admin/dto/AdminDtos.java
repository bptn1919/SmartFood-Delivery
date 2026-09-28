package com.amomeal.marketplace.admin.dto;

import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/admin/schemas.py. All keys are snake_case through the global Jackson naming
 * strategy. Money is {@code double} exactly like Django's {@code float(...)} resolvers.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // ---------------------------------------------------------------- users

    public record UserListItem(long id, String username, String email, String firstName, String lastName,
                               String phoneNumber, boolean isActive, boolean isStaff, List<String> groups,
                               String dateJoined) {
    }

    // ---------------------------------------------------------------- dashboard

    public record DashboardOverview(double totalRevenue, long totalOrders, long newUsers, long activeChefs,
                                    double cancellationRate) {
    }

    public record RevenueChartItem(String date, double revenue, long orders) {
    }

    public record RevenueChartResponse(String fromDate, String toDate, List<RevenueChartItem> data) {
    }

    public record PaymentMethodStatItem(String paymentMethod, long count, double percentage, double totalAmount) {
    }

    public record PaymentMethodStatsResponse(long totalOrders, List<PaymentMethodStatItem> data) {
    }

    public record OrderStatusStatItem(String status, long count, double percentage) {
    }

    public record OrderStatusStatsResponse(long totalOrders, List<OrderStatusStatItem> data) {
    }

    public record DistrictStatItem(String district, long successOrders, double percentage) {
    }

    public record DistrictStatsResponse(long totalSuccessOrders, List<DistrictStatItem> data) {
    }

    public record TopChefItem(long chefId, String chefName, String chefEmail, long totalOrders, double totalRevenue,
                              String avatarUrl) {
    }

    // ---------------------------------------------------------------- orders

    public record OrderListItem(UUID uid, String customerName, String customerEmail, String chefName,
                                String chefEmail, double totalPrice, double platformSubtotalDiscount,
                                double platformShippingDiscount, double shopDiscount, double totalDiscount,
                                String voucherCode, String status, String paymentStatus, String paymentMethod,
                                String createdAt, String deliveryDate) {
    }

    public record OrderItemView(String dishName, String dishImageUrl, int quantity, double price, double subtotal) {
    }

    public record OrderDetail(UUID uid, Long customerId, String customerName, String customerEmail,
                              String customerPhone, Long chefId, String chefName, String chefEmail,
                              List<OrderItemView> items, double subTotal, double taxAndFees, double deliveryFee,
                              double platformSubtotalDiscount, double platformShippingDiscount, double shopDiscount,
                              double totalDiscount, String voucherCode, double totalPrice, String status,
                              String paymentStatus, String paymentMethod, String deliveryAddress,
                              String deliveryDate, String deliveryTime, String createdAt, String updatedAt) {
    }

    // ---------------------------------------------------------------- vouchers

    /**
     * Django's VoucherDetailSchema PLUS discount_type. Django omits it, yet FE-admin's Vouchers page
     * filters on v.discount_type (so its "reward model" filter never matched); adding the key is purely
     * additive.
     */
    public record AdminVoucherResponse(UUID uid, String code, String name, String description, String voucherType,
                                       String discountType, BigDecimal discountValue, BigDecimal maxDiscountAmount,
                                       BigDecimal minOrderAmount, Instant startDate, Instant endDate,
                                       Integer usageLimit, long usageCount, int usageLimitPerUser, boolean isActive,
                                       Instant createdAt, Instant updatedAt) {
    }

    // ---------------------------------------------------------------- bank accounts

    public record BankUser(long id, String email, String username, String firstName, String lastName) {
    }

    /** CustomerPaymentInfoSchema. */
    public record CustomerBankAccount(long id, String bankName, String bankCode, String bankBranch,
                                      boolean isVerified, Instant verifiedAt, Instant createdAt, Instant updatedAt,
                                      String accountNumber, String accountHolderName, String email, BankUser user) {
    }

    /** ChefPaymentInfoSchema (adds citizen_id / tax_code / deleted). */
    public record ChefBankAccount(long id, String bankName, String bankCode, String bankBranch, String citizenId,
                                  String taxCode, boolean isVerified, Instant verifiedAt, Instant createdAt,
                                  Instant updatedAt, boolean deleted, String accountNumber,
                                  String accountHolderName, String email, BankUser user) {
    }

    // ---------------------------------------------------------------- verification review

    public record CertAttachmentItem(UUID attachmentUid, int position, String url) {
    }

    public record AdminCertificateDetail(UUID uid, String name, String certificateType, String status,
                                         String issuedBy, String issueDate, String expiryDate,
                                         String rejectionReason, String verifiedByEmail, String verifiedAt,
                                         List<CertAttachmentItem> attachments) {
    }

    public record VerificationReview(long userId, String userEmail, String decision, int riskScore,
                                     List<String> riskFlags, Double faceSimilarityScore,
                                     Map<String, Object> verifiedIdentity, String cccdNumberMasked,
                                     String selfieUrl, List<String> cccdImageUrls,
                                     List<AdminCertificateDetail> certificates, String verifiedAt) {
    }

    // ---------------------------------------------------------------- requests

    public record VerifyBankAccountRequest(@NotNull Boolean status) {
    }

    /**
     * AdminCreateVoucherSchema. Dates arrive as strings because FE-admin sends naive datetime-local
     * values ("2026-01-01T10:00") that pydantic accepts (Django stores them as UTC); see
     * AdminService.parseDateTime.
     */
    public record AdminCreateVoucherRequest(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            @NotNull VoucherType voucherType,
            @NotNull VoucherDiscountType discountType,
            @NotNull BigDecimal discountValue,
            BigDecimal maxDiscountAmount,
            BigDecimal minOrderAmount,
            @NotBlank String startDate,
            @NotBlank String endDate,
            Integer usageLimit,
            Integer usageLimitPerUser,
            Boolean isActive) {
    }
}
