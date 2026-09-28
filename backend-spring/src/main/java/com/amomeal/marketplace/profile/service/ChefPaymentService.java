package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.dto.ChefPaymentInfoRequest;
import com.amomeal.marketplace.profile.dto.ChefPaymentInfoResponse;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.entity.VietnamBank;
import com.amomeal.marketplace.profile.exception.ChefPaymentValidationException;
import com.amomeal.marketplace.profile.exception.ProfileDoesNotExistException;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Mirrors ../../backend/profile/services/__init__.py::ChefPaymentService.
 *
 * <p>PORT-NOTE: Django's {@code request_bank_verify_otp}/
 * {@code verify_chef_bank_info_otp} (the OTP-gated bank-verification flow that
 * {@code ../../backend/profile/api_chef_payment.py} exposes) are NOT ported —
 * see {@link com.amomeal.marketplace.profile.web.ChefPaymentController}'s
 * class javadoc for why that router is dead code in Django itself
 * (never mounted — {@code marketplace/urls.py} has
 * {@code #api.add_router("/chef", chef_payment_router)} commented out). Only
 * the plain create/get/delete flow from {@code profile/api.py}'s
 * {@code ChefPaymentController} (which IS live, via
 * {@code BaseAPI.auto_discover_controllers()}) is ported.
 *
 * <p>{@code exceptions/users.py}'s {@code BankNameRequired}/
 * {@code BankAccountNumberRequired}/{@code BankAccountNameRequired} are dead
 * imports in Django (`users/services.py` imports them but never raises them —
 * confirmed by grep) — required-ness is actually enforced by Pydantic's
 * schema (a missing field never reaches the service at all), which this port
 * mirrors with {@code @NotBlank} on {@link ChefPaymentInfoRequest} rather than
 * declaring three unreachable exception classes in a `users`-owned exceptions
 * file this task is not allowed to touch.
 */
@Service
@RequiredArgsConstructor
public class ChefPaymentService {

    private static final Pattern BANK_CODE = Pattern.compile("^\\d{6,8}$");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^\\d{6,19}$");
    private static final Pattern ACCOUNT_NAME_CHARS = Pattern.compile("^[A-Z\\s]+$");
    private static final Pattern CITIZEN_ID = Pattern.compile("^\\d{9,12}$");
    private static final Pattern TAX_CODE = Pattern.compile("^\\d{10,13}$");

    private final ChefPaymentInfoRepository chefPaymentInfoRepository;

    /**
     * Mirrors every {@code field_validator} on
     * ../../backend/profile/schemas/chef_payment.py::ChefPaymentInfoRequest, in
     * the same order, including the exact normalize-then-check sequence for
     * account name (regex check on the RAW value first, trim/collapse
     * whitespace second) and the "empty string skips validation" quirk on
     * citizen_id/tax_code ({@code if v and not re.match(...)} — Python treats
     * {@code ""} as falsy).
     */
    private NormalizedPayment validateAndNormalize(ChefPaymentInfoRequest req) {
        if (!VietnamBank.isValid(req.bankName())) {
            throw new ChefPaymentValidationException("bank_name", "Invalid bank name");
        }

        String bankCode = req.bankCode().strip().replace(" ", "");
        if (!BANK_CODE.matcher(bankCode).matches()) {
            throw new ChefPaymentValidationException("bank_code", "Bank code must be 6-8 digits");
        }

        String accountNumber = req.bankAccountNumber().replace(" ", "").replace("-", "");
        if (!ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw new ChefPaymentValidationException("bank_account_number",
                    "Account number must be 6-19 digits only (no letters or special characters)");
        }

        String rawAccountName = req.bankAccountName();
        if (rawAccountName == null || !ACCOUNT_NAME_CHARS.matcher(rawAccountName).matches()) {
            throw new ChefPaymentValidationException("bank_account_name",
                    "Account name must be UPPERCASE letters without accents (A-Z and spaces only). Example: NGUYEN VAN A");
        }
        String accountName = rawAccountName.strip().replaceAll("\\s+", " ");
        if (accountName.length() < 2) {
            throw new ChefPaymentValidationException("bank_account_name", "Account name too short");
        }

        String citizenId = req.citizenId();
        if (citizenId != null && !citizenId.isEmpty() && !CITIZEN_ID.matcher(citizenId).matches()) {
            throw new ChefPaymentValidationException("citizen_id", "Citizen ID must be 9-12 digits");
        }

        String taxCode = req.taxCode();
        if (taxCode != null && !taxCode.isEmpty() && !TAX_CODE.matcher(taxCode).matches()) {
            throw new ChefPaymentValidationException("tax_code", "Tax code must be 10-13 digits");
        }

        return new NormalizedPayment(req.bankName(), bankCode, accountNumber, accountName,
                req.bankBranch(), citizenId, taxCode);
    }

    private record NormalizedPayment(String bankName, String bankCode, String bankAccountNumber,
                                      String bankAccountName, String bankBranch, String citizenId, String taxCode) {
    }

    /** Mirrors {@code ChefPaymentService.create_or_update_payment_info}. */
    @Transactional
    public ChefPaymentInfo createOrUpdatePaymentInfo(CustomUser user, ChefPaymentInfoRequest payload) {
        NormalizedPayment n = validateAndNormalize(payload);
        ChefPaymentInfo info = chefPaymentInfoRepository.findByUser(user).orElseGet(() -> ChefPaymentInfo.builder()
                .user(user)
                .build());
        info.setBankName(n.bankName());
        info.setBankCode(n.bankCode());
        info.setBankAccountNumber(n.bankAccountNumber());
        info.setBankAccountName(n.bankAccountName());
        info.setBankBranch(n.bankBranch());
        info.setCitizenId(n.citizenId());
        info.setTaxCode(n.taxCode());
        info.setVerified(false);
        return chefPaymentInfoRepository.save(info);
    }

    /** Mirrors {@code ChefPaymentService.get_payment_info}. */
    @Transactional(readOnly = true)
    public ChefPaymentInfo getPaymentInfo(CustomUser user) {
        return chefPaymentInfoRepository.findByUser(user).orElseThrow(ProfileDoesNotExistException::new);
    }

    /** Mirrors {@code ChefPaymentService.delete_payment_info} — soft delete only. */
    @Transactional
    public boolean deletePaymentInfo(CustomUser user) {
        ChefPaymentInfo info = chefPaymentInfoRepository.findByUser(user).orElseThrow(ProfileDoesNotExistException::new);
        info.setDeleted(true);
        chefPaymentInfoRepository.save(info);
        return true;
    }

    /** Mirrors {@code ChefPaymentService.mask_account_number}. */
    public String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return accountNumber;
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    public ChefPaymentInfoResponse toMaskedResponse(ChefPaymentInfo info) {
        return ChefPaymentInfoResponse.of(info, maskAccountNumber(info.getBankAccountNumber()));
    }
}
