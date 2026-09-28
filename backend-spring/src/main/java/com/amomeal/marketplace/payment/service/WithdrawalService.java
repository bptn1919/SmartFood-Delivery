package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.payment.config.PaymentProperties;
import com.amomeal.marketplace.payment.entity.BankInfo;
import com.amomeal.marketplace.payment.entity.InternalWallet;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionState;
import com.amomeal.marketplace.payment.entity.WalletTransactionStatus;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.entity.WithdrawalFailureLog;
import com.amomeal.marketplace.payment.exception.PaymentHttpException;
import com.amomeal.marketplace.payment.provider.PayOsProvider;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.InternalWalletRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionStateRepository;
import com.amomeal.marketplace.payment.repository.WithdrawalFailureLogRepository;
import com.amomeal.marketplace.security.InvalidOrExpiredTokenException;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserOtp;
import com.amomeal.marketplace.users.service.AuthEmailService;
import com.amomeal.marketplace.users.service.OtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Port of the wallet PAYOUT (withdrawal) flow of ../../backend/payment/services.py:
 * {@code request_withdraw_otp} → {@code confirm_withdraw_with_otp} →
 * {@code withdraw_internal_wallet}, with {@code _create_payout_with_retry},
 * {@code _should_retry_payout}, {@code _get_daily_withdrawal_sum},
 * {@code _record_withdrawal_failure}, {@code _send_withdraw_failure_email}.
 *
 * <h2>Money safety of {@link #withdrawInternalWallet} (Django's, preserved)</h2>
 * One transaction, holding {@code SELECT ... FOR UPDATE} on the wallet row the whole time:
 * re-check integrity (signature + ledger) and {@code balance >= amount} UNDER the lock (the
 * OTP-time check is not trusted), move {@code amount} from {@code balance} to
 * {@code pending_balance}, append a PENDING PAYOUT ledger line, call PayOS (with retries), then
 * either settle ({@code pending -= amount}, ledger SUCCESS) or revert
 * ({@code balance += amount}, {@code pending -= amount}, ledger FAILED) — all in that same
 * transaction, so a failed payout never leaves the wallet debited. Two concurrent confirms
 * serialize on the lock; the second sees the reduced balance → "Insufficient internal wallet
 * balance". {@code balance} can therefore never go negative.
 *
 * <p>PORT-NOTE (preserved, flagged): the PayOS payout HTTP call (up to 3 × SDK retries, with
 * sleeps) happens INSIDE the transaction while the wallet row is locked. If the process dies
 * after PayOS accepted the payout but before COMMIT, the debit rolls back although the money
 * left — Django has exactly this window.
 *
 * <p>In Django the integrity check never passed once a wallet had activity, so this flow was
 * unreachable; fixed here (payment open question #2 — canonical 2-dp hashing, signed new
 * wallets, re-sign after every balance change including the successful payout).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalService {

    static final BigDecimal MIN_WITHDRAWAL = new BigDecimal("10000");
    static final BigDecimal MAX_DAILY_WITHDRAWAL = new BigDecimal("20000000");

    private final WalletService walletService;
    private final BankInfoService bankInfoService;
    private final InternalWalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletTransactionStateRepository walletTransactionStateRepository;
    private final WithdrawalFailureLogRepository failureLogRepository;
    private final PayOsProvider payOsProvider;
    private final PaymentOtpVerifier otpVerifier;
    private final OtpService otpService;
    private final AuthEmailService authEmailService;
    private final PaymentEmailService paymentEmailService;
    private final PaymentProperties properties;
    private final PaymentHmac hmac;
    private final PaymentTx tx;

    /** Python {@code f"{int(x):,}"}. */
    static String thousands(BigDecimal value) {
        return String.format(Locale.US, "%,d", value.toBigInteger());
    }

    // =====================================================================
    // request_withdraw_otp
    // =====================================================================

    /**
     * Django {@code request_withdraw_otp(user, amount)} — validation cascade in Django's exact
     * order, each failure an {@code HttpError(400, ...)}; then a 2-minute WITHDRAW_VERIFY OTP whose
     * {@code target_email} column stores the quantized amount (so confirm cannot change it).
     */
    public String requestWithdrawOtp(CustomUser user, BigDecimal rawAmount) {
        BigDecimal amount = WalletService.quantize(rawAmount);
        if (amount.signum() <= 0) {
            throw badRequest("Withdrawal amount must be greater than zero");
        }
        if (amount.compareTo(MIN_WITHDRAWAL) < 0) {
            throw badRequest("Minimum withdrawal amount is " + thousands(MIN_WITHDRAWAL) + " VND");
        }
        BigDecimal remainingDaily = MAX_DAILY_WITHDRAWAL.subtract(dailyWithdrawalSum(user.getId()));
        if (amount.compareTo(remainingDaily) > 0) {
            throw badRequest("Daily withdrawal limit exceeded. Remaining today: " + thousands(remainingDaily) + " VND");
        }
        BankInfo bankInfo = bankInfoService.getUserBankInfo(user);
        if (bankInfo == null) {
            throw badRequest("Bank information not found. Please add bank information first.");
        }
        if (!bankInfo.isVerified()) {
            throw badRequest("Bank information is not verified");
        }
        InternalWallet wallet = walletService.getOrCreateWallet(user.getId());
        WalletService.IntegrityReport integrity = walletService.verifyIntegrity(wallet);
        if (!integrity.signatureValid() || !integrity.balanceMatchesLedger() || !integrity.chainIntact()) {
            log.error("Wallet integrity violation user={} sig={} ledger={} chain={} break_at={} reason={}",
                    user.getId(), integrity.signatureValid(), integrity.balanceMatchesLedger(), integrity.chainIntact(),
                    integrity.chainBreakAt(), integrity.chainBreakReason());
            throw badRequest("Wallet integrity check failed");
        }
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw badRequest("Insufficient internal wallet balance");
        }
        otpService.deactivateAllForUser(user.getId());
        OtpService.CreatedOtp created = otpService.create(user.getId(), OtpPurpose.WITHDRAW_VERIFY, PyCompat.decimal(amount));
        authEmailService.sendVerificationEmail(user, created.plainOtp(), OtpPurpose.WITHDRAW_VERIFY, null);
        return created.record().getResetSessionToken();
    }

    /** Django {@code _get_daily_withdrawal_sum}: today's (UTC) SUCCESS payouts by {@code processed_at}. */
    BigDecimal dailyWithdrawalSum(Long userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant start = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1000);
        BigDecimal total = tx.required(() -> walletTransactionRepository.sumProcessedBetween(userId,
                WalletTransactionType.PAYOUT, WalletTransactionStatus.SUCCESS, start, end));
        return WalletService.quantize(total);
    }

    private static PaymentHttpException badRequest(String message) {
        return new PaymentHttpException(HttpStatus.BAD_REQUEST, message);
    }

    // =====================================================================
    // confirm_withdraw_with_otp
    // =====================================================================

    /**
     * Django {@code confirm_withdraw_with_otp(user, reset_session_token, otp)}: verify the OTP,
     * read the amount stored at request time (unparsable → INVALID_OR_EXPIRED_TOKEN), consume the
     * OTP (committed on its own, before the payout), then {@link #withdrawInternalWallet}.
     */
    public Map<String, Object> confirmWithdrawWithOtp(CustomUser user, String resetSessionToken, String otp) {
        UserOtp record = otpVerifier.verify(user, resetSessionToken, otp, OtpPurpose.WITHDRAW_VERIFY);
        BigDecimal storedAmount;
        try {
            storedAmount = WalletService.quantize(new BigDecimal(record.getTargetEmail()));
        } catch (NullPointerException | NumberFormatException ex) {
            throw new InvalidOrExpiredTokenException();
        }
        otpVerifier.consume(record);
        Map<String, Object> result = withdrawInternalWallet(user, storedAmount);
        result.putIfAbsent("amount", storedAmount.doubleValue());
        return result;
    }

    // =====================================================================
    // withdraw_internal_wallet
    // =====================================================================

    /** Django {@code withdraw_internal_wallet(user, amount)} — never throws; see the class javadoc. */
    public Map<String, Object> withdrawInternalWallet(CustomUser user, BigDecimal rawAmount) {
        BigDecimal amount = WalletService.quantize(rawAmount);
        if (amount.signum() <= 0) {
            return PaymentRefundService.result("success", false, "error", "Withdrawal amount must be greater than zero");
        }
        BankInfo bankInfo = bankInfoService.getUserBankInfo(user);
        if (bankInfo == null) {
            return PaymentRefundService.result("success", false,
                    "error", "Bank information not found. Please add bank information first.");
        }
        if (!bankInfo.isVerified()) {
            return PaymentRefundService.result("success", false, "error", "Bank information is not verified");
        }

        String referenceId = "wallet_withdraw_" + user.getId() + "_" + Instant.now().getEpochSecond();
        String idempotencyKey = UUID.randomUUID().toString();
        AtomicReference<WalletTransaction> walletTxRef = new AtomicReference<>();
        Map<String, Object> bankMetadata = new LinkedHashMap<>();
        bankMetadata.put("bank_name", bankInfo.getBankName());
        bankMetadata.put("bank_code", bankInfo.getBankCode());
        // PORT-NOTE (preserved, flagged): the FULL account number goes into the ledger state's
        // metadata here, while the failure log below masks it — inconsistent PII handling.
        bankMetadata.put("bank_account_number", bankInfo.getBankAccountNumber());
        bankMetadata.put("bank_account_name", bankInfo.getBankAccountName());
        bankMetadata.put("bank_branch", bankInfo.getBankBranch());

        try {
            return tx.required(() -> withdrawLocked(user, amount, bankInfo, referenceId, idempotencyKey,
                    bankMetadata, walletTxRef));
        } catch (RuntimeException exc) {
            WalletTransaction rolledBack = walletTxRef.get();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("reference_id", referenceId);
            metadata.put("bank_account", maskAccountNumber(bankInfo.getBankAccountNumber()));
            recordWithdrawalFailureInOwnTransaction(user.getId(), amount, "EXCEPTION",
                    exc.getClass().getSimpleName(), String.valueOf(exc.getMessage()),
                    rolledBack != null ? rolledBack.getId() : null, metadata);
            paymentEmailService.sendWithdrawFailedEmail(user, thousands(amount), referenceId,
                    "Unexpected error while processing withdrawal");
            log.error("Withdrawal failed: {}", exc.toString(), exc);
            return PaymentRefundService.result("success", false, "error", "Withdrawal failed. Please try again later.",
                    "status", "FAILED", "reference_id", referenceId);
        }
    }

    private Map<String, Object> withdrawLocked(CustomUser user, BigDecimal amount, BankInfo bankInfo, String referenceId,
                                               String idempotencyKey, Map<String, Object> bankMetadata,
                                               AtomicReference<WalletTransaction> walletTxRef) {
        InternalWallet wallet = walletService.lockOrCreate(user.getId());
        WalletService.IntegrityReport integrity = walletService.verifyIntegrity(wallet);
        if (!integrity.signatureValid() || !integrity.balanceMatchesLedger()) {
            return PaymentRefundService.result("success", false, "error", "Wallet integrity check failed", "status", "FAILED");
        }
        if (wallet.getBalance().compareTo(amount) < 0) {
            return PaymentRefundService.result("success", false, "error", "Insufficient internal wallet balance");
        }

        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setBalance(WalletService.quantize(wallet.getBalance().subtract(amount)));
        wallet.setPendingBalance(WalletService.quantize(wallet.getPendingBalance().add(amount)));

        UUID txUid = UUID.randomUUID();
        String previousHash = walletService.lastChainHash(user.getId());
        String chainHash = hmac.walletChainHash(txUid, user.getId(), WalletTransactionType.PAYOUT.name(),
                amount, balanceBefore, wallet.getBalance(), previousHash);
        wallet.setSignature(walletService.computeSignature(wallet));
        walletRepository.saveAndFlush(wallet);

        WalletTransaction walletTx = walletTransactionRepository.saveAndFlush(WalletTransaction.builder()
                .uid(txUid)
                .userId(user.getId())
                .transactionType(WalletTransactionType.PAYOUT)
                .amount(amount)
                .referenceId(referenceId)
                .balanceBefore(balanceBefore)
                .balanceAfter(wallet.getBalance())
                .previousHash(previousHash)
                .chainHash(chainHash)
                .build());
        walletTxRef.set(walletTx);
        WalletTransactionState walletTxState = walletTransactionStateRepository.saveAndFlush(WalletTransactionState.builder()
                .walletTransactionId(walletTx.getId())
                .status(WalletTransactionStatus.PENDING)
                .description("Wallet withdrawal request")
                .metadata(bankMetadata)
                .build());

        Map<String, Object> payoutResult = createPayoutWithRetry(referenceId, amount.longValue(), "Wallet withdrawal",
                bankInfo.getBankCode(), bankInfo.getBankAccountNumber(), idempotencyKey, List.of("wallet_withdrawal"));

        if (!PyCompat.truthy(payoutResult.get("success"))) {
            wallet.setBalance(WalletService.quantize(wallet.getBalance().add(amount)));
            wallet.setPendingBalance(WalletService.quantize(wallet.getPendingBalance().subtract(amount)));
            wallet.setSignature(walletService.computeSignature(wallet));
            walletRepository.saveAndFlush(wallet);

            Object error = payoutResult.getOrDefault("error", "Payout failed");
            walletTxState.setStatus(WalletTransactionStatus.FAILED);
            walletTxState.setDescription(PyCompat.str(error));
            walletTxState.setMetadata(withPayoutResult(walletTxState.getMetadata(), payoutResult));
            walletTransactionStateRepository.saveAndFlush(walletTxState);

            Map<String, Object> failureMetadata = new LinkedHashMap<>();
            failureMetadata.put("reference_id", referenceId);
            failureMetadata.put("payout_result", payoutResult);
            failureMetadata.put("bank_account", maskAccountNumber(bankInfo.getBankAccountNumber()));
            recordWithdrawalFailure(user.getId(), amount, "PAYOUT_FAILED", "Error", PyCompat.str(error),
                    walletTx.getId(), failureMetadata);
            paymentEmailService.sendWithdrawFailedEmail(user, thousands(amount), referenceId, PyCompat.str(error));

            return PaymentRefundService.result("success", false,
                    "error", payoutResult.getOrDefault("error", "Failed to create payout"),
                    "status", "FAILED", "reference_id", referenceId);
        }

        // FIX (payment open question #2): Django saves only pending_balance here and does NOT
        // re-sign, so after a successful payout the stored signature no longer matched and the
        // wallet was locked for good. Re-signed over the new balances.
        wallet.setPendingBalance(WalletService.quantize(wallet.getPendingBalance().subtract(amount)));
        wallet.setSignature(walletService.computeSignature(wallet));
        walletRepository.saveAndFlush(wallet);
        walletTxState.setStatus(WalletTransactionStatus.SUCCESS);
        walletTxState.setPayoutId(payoutResult.get("payout_id") == null ? null : String.valueOf(payoutResult.get("payout_id")));
        walletTxState.setMetadata(withPayoutResult(walletTxState.getMetadata(), payoutResult));
        walletTxState.setProcessedAt(Instant.now());
        walletTransactionStateRepository.saveAndFlush(walletTxState);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "Withdrawal processed successfully");
        out.put("status", "SUCCESS");
        out.put("payout_id", payoutResult.get("payout_id"));
        out.put("reference_id", referenceId);
        out.put("amount", amount.doubleValue());
        out.put("bank_account", bankInfo.getBankName() + " - " + maskAccountNumber(bankInfo.getBankAccountNumber()));
        return out;
    }

    private static Map<String, Object> withPayoutResult(Map<String, Object> metadata, Map<String, Object> payoutResult) {
        Map<String, Object> merged = PaymentRefundService.copyOf(metadata);
        merged.put("payout_result", payoutResult);
        return merged;
    }

    // =====================================================================
    // _create_payout_with_retry / _should_retry_payout
    // =====================================================================

    /** Django {@code _create_payout_with_retry}: up to 3 attempts, backoff 0.6 s then doubled (no jitter). */
    Map<String, Object> createPayoutWithRetry(String referenceId, long amount, String description, String toBin,
                                              String toAccountNumber, String idempotencyKey, List<String> category) {
        int maxAttempts = 3;
        long backoff = properties.getPayoutRetryBackoffMs();
        Map<String, Object> last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Map<String, Object> result = payOsProvider.createPayout(referenceId, amount, description, toBin,
                    toAccountNumber, idempotencyKey, category);
            last = result;
            if (PyCompat.truthy(result.get("success"))) {
                return result;
            }
            if (!shouldRetryPayout(result, attempt, maxAttempts)) {
                return result;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            backoff *= 2;
        }
        return last != null ? last : PaymentRefundService.result("success", false, "error", "Payout failed");
    }

    /** Django {@code _should_retry_payout}. */
    static boolean shouldRetryPayout(Map<String, Object> payoutResult, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return false;
        }
        Object statusCode = payoutResult.get("status_code");
        if (statusCode instanceof Number n && List.of(408, 429, 500, 502, 503, 504).contains(n.intValue())) {
            return true;
        }
        String error = PyCompat.str(payoutResult.getOrDefault("error", "")).toLowerCase();
        for (String token : List.of("timeout", "temporarily", "connection", "rate limit")) {
            if (error.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // failure log / masking
    // =====================================================================

    /** Django {@code _mask_account_number}: all but the last 4 digits replaced by '*'. */
    static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return "";
        }
        if (accountNumber.length() <= 4) {
            return accountNumber;
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    /** Django {@code _record_withdrawal_failure} inside the caller's transaction (swallows its own errors). */
    private void recordWithdrawalFailure(Long userId, BigDecimal amount, String stage, String errorType, String message,
                                         Long walletTxId, Map<String, Object> metadata) {
        try {
            failureLogRepository.save(WithdrawalFailureLog.builder()
                    .userId(userId)
                    .walletTransactionId(walletTxId)
                    .amount(WalletService.quantize(amount))
                    .stage(stage)
                    .errorType(errorType)
                    .errorMessage(message)
                    .metadata(metadata)
                    .build());
        } catch (RuntimeException ex) {
            log.error("Failed to record withdrawal failure: {}", ex.toString());
        }
    }

    /**
     * The same, for Django's {@code except Exception} branch — which runs AFTER the atomic block
     * rolled back, i.e. under autocommit. PORT-NOTE (preserved): if the ledger row was already
     * inserted before the exception, Django passes that rolled-back row as
     * {@code wallet_transaction} → the FK insert fails → the failure log itself is lost (only
     * logged). Reproduced.
     */
    private void recordWithdrawalFailureInOwnTransaction(Long userId, BigDecimal amount, String stage, String errorType,
                                                         String message, Long walletTxId, Map<String, Object> metadata) {
        try {
            tx.requiresNew(() -> failureLogRepository.saveAndFlush(WithdrawalFailureLog.builder()
                    .userId(userId)
                    .walletTransactionId(walletTxId)
                    .amount(WalletService.quantize(amount))
                    .stage(stage)
                    .errorType(errorType)
                    .errorMessage(message)
                    .metadata(metadata)
                    .build()));
        } catch (RuntimeException ex) {
            log.error("Failed to record withdrawal failure: {}", ex.toString());
        }
    }
}
