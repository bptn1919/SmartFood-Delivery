package com.amomeal.marketplace.payment.entity;

/**
 * The attributes Django's {@code PaymentService} reads off "whatever bank info the user has"
 * via {@code getattr(bank_info, 'bank_code', None)} etc. — either a
 * {@link CustomerPaymentInfo} or a {@code profile.ChefPaymentInfo}
 * ({@code _get_user_bank_info}: customer row first, chef row second).
 */
public interface BankInfo {

    String getBankName();

    String getBankCode();

    String getBankAccountNumber();

    String getBankAccountName();

    String getBankBranch();

    boolean isVerified();
}
