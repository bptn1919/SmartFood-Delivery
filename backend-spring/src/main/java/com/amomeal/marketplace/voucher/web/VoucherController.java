package com.amomeal.marketplace.voucher.web;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.dto.CreateVoucherRequest;
import com.amomeal.marketplace.voucher.dto.UpdateVoucherRequest;
import com.amomeal.marketplace.voucher.dto.ValidateVoucherRequest;
import com.amomeal.marketplace.voucher.dto.ValidateVoucherResponse;
import com.amomeal.marketplace.voucher.dto.VoucherDetailResponse;
import com.amomeal.marketplace.voucher.dto.VoucherListResponse;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.service.VoucherService;
import com.amomeal.marketplace.voucher.service.VoucherValidationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/voucher/api.py::VoucherController. Base prefix {@code "vouchers"}
 * under the shared {@code /api} root (Django's {@code @api(prefix_or_class="vouchers", ...)}),
 * so {@code POST /api/vouchers}, {@code GET /api/vouchers}, {@code POST /api/vouchers/validate},
 * {@code GET /api/vouchers/chef/{chef_id}}, {@code GET /api/vouchers/{voucher_uid}},
 * {@code PATCH /api/vouchers/{voucher_uid}}, {@code DELETE /api/vouchers/{voucher_uid}},
 * {@code GET /api/vouchers/code/{code}}.
 *
 * <p><b>No {@code @PreAuthorize} on any endpoint here — confirmed against Django, not an
 * oversight.</b> {@code api.py} is declared with {@code auth=AuthBear()} only; there is no
 * {@code @require_group}/{@code @require_permission} decorator on a single route in the whole
 * file, including {@code create_voucher} (whose docstring says "Chef only"; post-port,
 * {@link VoucherService#createVoucher} enforces CHEF-or-ADMIN with a service-thrown 403). Every route below only
 * requires <em>some</em> authenticated principal, exactly matching Spring Security's default
 * {@code anyRequest().authenticated()}. Ownership (not role) gates {@code update}/{@code delete}
 * inside the service, with the ADMIN-group-or-{@code is_staff} bypass documented there.
 */
@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    /** Django: {@code POST /api/vouchers} ({@code create_voucher}). */
    @PostMapping
    public VoucherDetailResponse createVoucher(@AuthenticationPrincipal CustomUser user,
                                                @Valid @RequestBody CreateVoucherRequest payload) {
        Voucher voucher = voucherService.createVoucher(user, payload);
        return VoucherDetailResponse.from(voucher, voucherService.usageCount(voucher));
    }

    /** Django: {@code GET /api/vouchers} ({@code list_my_vouchers}). */
    @GetMapping
    public List<VoucherListResponse> listMyVouchers(@AuthenticationPrincipal CustomUser user) {
        return voucherService.getMyVouchers(user).stream().map(VoucherListResponse::from).toList();
    }

    /** Django: {@code POST /api/vouchers/validate} ({@code validate_voucher}). */
    @PostMapping("/validate")
    public ValidateVoucherResponse validateVoucher(@AuthenticationPrincipal CustomUser user,
                                                    @Valid @RequestBody ValidateVoucherRequest payload) {
        VoucherValidationResult result = voucherService.validateVoucherForOrder(
                payload.code(), payload.orderAmount(), payload.chefId(), user);

        BigDecimal discountAmount = result.valid() ? result.discountAmount() : null;
        BigDecimal finalAmount = result.valid() ? payload.orderAmount().subtract(result.discountAmount()) : null;
        return new ValidateVoucherResponse(result.valid(), result.message(), discountAmount, finalAmount);
    }

    /** Django: {@code GET /api/vouchers/chef/{chef_id}} ({@code list_vouchers_by_chef}). */
    @GetMapping("/chef/{chefId}")
    public List<VoucherListResponse> listVouchersByChef(@AuthenticationPrincipal CustomUser user,
                                                         @PathVariable Long chefId,
                                                         @RequestParam(name = "available_only", defaultValue = "true") boolean availableOnly) {
        return voucherService.getVouchersByChef(chefId, availableOnly, user).stream()
                .map(VoucherListResponse::from).toList();
    }

    /** Django: {@code GET /api/vouchers/{voucher_uid}} ({@code get_voucher}). */
    @GetMapping("/{voucherUid}")
    public VoucherDetailResponse getVoucher(@PathVariable UUID voucherUid) {
        Voucher voucher = voucherService.getVoucherByUid(voucherUid);
        return VoucherDetailResponse.from(voucher, voucherService.usageCount(voucher));
    }

    /** Django: {@code PATCH /api/vouchers/{voucher_uid}} ({@code update_voucher}). */
    @PatchMapping("/{voucherUid}")
    public VoucherDetailResponse updateVoucher(@AuthenticationPrincipal CustomUser user,
                                                @PathVariable UUID voucherUid,
                                                @RequestBody UpdateVoucherRequest payload) {
        Voucher voucher = voucherService.updateVoucher(voucherUid, user, payload);
        return VoucherDetailResponse.from(voucher, voucherService.usageCount(voucher));
    }

    /** Django: {@code DELETE /api/vouchers/{voucher_uid}} ({@code delete_voucher}), response=bool. */
    @DeleteMapping("/{voucherUid}")
    public boolean deleteVoucher(@AuthenticationPrincipal CustomUser user, @PathVariable UUID voucherUid) {
        voucherService.deleteVoucher(voucherUid, user);
        return true;
    }

    /** Django: {@code GET /api/vouchers/code/{code}} ({@code get_voucher_by_code}). */
    @GetMapping("/code/{code}")
    public VoucherDetailResponse getVoucherByCode(@PathVariable String code) {
        Voucher voucher = voucherService.getVoucherByCode(code);
        return VoucherDetailResponse.from(voucher, voucherService.usageCount(voucher));
    }
}
