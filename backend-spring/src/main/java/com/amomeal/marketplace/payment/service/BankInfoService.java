package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.payment.dto.CustomerPaymentInfoRequest;
import com.amomeal.marketplace.payment.entity.BankInfo;
import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.exception.PaymentHttpException;
import com.amomeal.marketplace.payment.repository.CustomerPaymentInfoRepository;
import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.profile.repository.ChefPaymentInfoRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.service.AuthEmailService;
import com.amomeal.marketplace.users.service.OtpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Port of the customer bank-account half of ../../backend/payment/services.py:
 * {@code request_bank_verify_otp}, {@code verify_bank_info_otp},
 * {@code get_customer_payment_info}, {@code _get_user_bank_info}.
 * ({@code create_or_update_customer_payment_info} is called from nowhere in Django — not ported.)
 */
@Service
@RequiredArgsConstructor
public class BankInfoService {

    private final CustomerPaymentInfoRepository customerPaymentInfoRepository;
    private final ChefPaymentInfoRepository chefPaymentInfoRepository;
    private final PaymentOtpVerifier otpVerifier;
    private final OtpService otpService;
    private final AuthEmailService authEmailService;
    private final PaymentTx tx;

    /**
     * Django {@code _get_user_bank_info(user)}: the user's {@code CustomerPaymentInfo} if any,
     * else their {@code ChefPaymentInfo} (the {@code profile} row — regardless of its
     * {@code deleted} flag, like Django's reverse accessor), else {@code None}.
     */
    public BankInfo getUserBankInfo(CustomUser user) {
        return tx.required(() -> {
            Optional<CustomerPaymentInfo> customer = customerPaymentInfoRepository.findByUserId(user.getId());
            if (customer.isPresent()) {
                return (BankInfo) customer.get();
            }
            return chefPaymentInfoRepository.findByUser(user).map(ChefBankInfo::new).orElse(null);
        });
    }

    /** {@code profile.ChefPaymentInfo} seen through {@link BankInfo}. */
    private record ChefBankInfo(String getBankName, String getBankCode, String getBankAccountNumber,
                                String getBankAccountName, String getBankBranch, boolean isVerified) implements BankInfo {
        ChefBankInfo(ChefPaymentInfo info) {
            this(info.getBankName(), info.getBankCode(), info.getBankAccountNumber(), info.getBankAccountName(),
                    info.getBankBranch(), info.isVerified());
        }
    }

    /** Django {@code get_customer_payment_info(user)}. */
    public Optional<CustomerPaymentInfo> getCustomerPaymentInfo(CustomUser user) {
        return tx.required(() -> customerPaymentInfoRepository.findByUserId(user.getId()));
    }

    /**
     * Django {@code request_bank_verify_otp(user, payload)}: save the bank info UNVERIFIED, burn
     * the user's live OTPs, email a 2-minute BANK_VERIFY OTP, return its session token.
     */
    public String requestBankVerifyOtp(CustomUser user, CustomerPaymentInfoRequest payload) {
        tx.required(() -> {
            CustomerPaymentInfo info = customerPaymentInfoRepository.findByUserId(user.getId())
                    .orElseGet(() -> CustomerPaymentInfo.builder().userId(user.getId()).build());
            info.setBankName(payload.bankName());
            info.setBankCode(payload.bankCode());
            info.setBankAccountNumber(payload.bankAccountNumber());
            info.setBankAccountName(payload.bankAccountName());
            info.setBankBranch(payload.bankBranch());
            info.setVerified(false);
            info.setVerifiedAt(null);
            customerPaymentInfoRepository.saveAndFlush(info);
        });
        otpService.deactivateAllForUser(user.getId());
        OtpService.CreatedOtp created = otpService.create(user.getId(), OtpPurpose.BANK_VERIFY, null);
        authEmailService.sendVerificationEmail(user, created.plainOtp(), OtpPurpose.BANK_VERIFY, null);
        return created.record().getResetSessionToken();
    }

    /** Django {@code verify_bank_info_otp(user, reset_session_token, otp)}. */
    public CustomerPaymentInfo verifyBankInfoOtp(CustomUser user, String resetSessionToken, String otp) {
        UserOtp record = otpVerifier.verify(user, resetSessionToken, otp, OtpPurpose.BANK_VERIFY);
        otpVerifier.consume(record);
        return tx.required(() -> {
            CustomerPaymentInfo info = customerPaymentInfoRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new PaymentHttpException(HttpStatus.BAD_REQUEST,
                            "Bank info not found. Please submit bank info first."));
            info.setVerified(true);
            info.setVerifiedAt(Instant.now());
            return customerPaymentInfoRepository.saveAndFlush(info);
        });
    }
}
