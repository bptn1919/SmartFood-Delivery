package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.entity.InternalWallet;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionState;
import com.amomeal.marketplace.payment.entity.WalletTransactionStatus;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.entity.WithdrawalFailureLog;
import com.amomeal.marketplace.payment.repository.CustomerPaymentInfoRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionStateRepository;
import com.amomeal.marketplace.payment.repository.WithdrawalFailureLogRepository;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The internal-wallet ledger against a real Postgres: credits (escrow RELEASE / REFUND),
 * the hash chain and signature (canonical 2-dp amounts: Django's scale bug fixed), the append-only
 * trigger, and the withdrawal (PAYOUT) flow with the PayOS payout faked over real HTTP —
 * success, gateway failure with full revert, insufficient funds, and concurrent attempts
 * (the balance can never go negative / be spent twice).
 */
class WalletLedgerTest extends com.amomeal.marketplace.payment.web.AbstractPaymentFullStackTest {

    @Autowired WalletService walletService;
    @Autowired WithdrawalService withdrawalService;
    @Autowired WalletTransactionStateRepository walletTxStateRepository;
    @Autowired WithdrawalFailureLogRepository failureLogRepository;
    @Autowired CustomerPaymentInfoRepository customerPaymentInfoRepository;

    private List<WalletTransaction> chainOf(Long userId) {
        return walletTransactionRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
    }

    private void verifiedBank(Account who) {
        customerPaymentInfoRepository.save(CustomerPaymentInfo.builder().userId(who.user().getId())
                .bankName("Vietcombank").bankCode("970436").bankAccountNumber("0123456789")
                .bankAccountName("NGUYEN VAN A").verified(true).build());
    }

    @Test
    void credits_appendAChainedLedger_hashedOverCanonicalAmounts() throws Exception {
        Account user = register("credit", UserRole.CUSTOMER);
        Long id = user.user().getId();

        WalletTransaction t1 = walletService.credit(id, new BigDecimal("100000.40"), WalletTransactionType.REFUND,
                null, "refund_x", "Refund", Map.of("k", "v"));
        WalletTransaction t2 = walletService.credit(id, new BigDecimal("50000"), WalletTransactionType.RELEASE,
                null, "release_y", "Release", null);

        InternalWallet wallet = walletRepository.findByUserId(id).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("150000");
        assertThat(wallet.getPendingBalance()).isEqualByComparingTo("0");
        assertThat(t1.getAmount()).isEqualByComparingTo("100000"); // quantized HALF_UP to whole VND

        // Every amount is hashed in its canonical 2-dp form, so write-time and read-back agree.
        assertThat(t1.getPreviousHash()).isEqualTo(WalletTransaction.GENESIS_HASH);
        assertThat(t1.getChainHash()).isEqualTo(paymentHmac.walletChainHash(t1.getUid(), id, "REFUND",
                new BigDecimal("100000.00"), new BigDecimal("0.00"), new BigDecimal("100000.00"), WalletTransaction.GENESIS_HASH));
        assertThat(t2.getPreviousHash()).isEqualTo(t1.getChainHash());
        assertThat(t2.getChainHash()).isEqualTo(paymentHmac.walletChainHash(t2.getUid(), id, "RELEASE",
                new BigDecimal("50000"), new BigDecimal("100000"), new BigDecimal("150000"), t1.getChainHash()));
        assertThat(wallet.getSignature()).isEqualTo(
                paymentHmac.walletSignature(id, new BigDecimal("150000.00"), new BigDecimal("0.00")));
        WalletService.IntegrityReport report = walletService.verifyIntegrity(walletService.getOrCreateWallet(id));
        assertThat(report.signatureValid()).isTrue();
        assertThat(report.balanceMatchesLedger()).isTrue();
        assertThat(report.chainIntact()).isTrue();
        assertThat(report.chainLength()).isEqualTo(2);

        List<WalletTransaction> chain = chainOf(id);
        assertThat(chain).extracting(WalletTransaction::getUid).containsExactly(t1.getUid(), t2.getUid());
        assertThat(chain.get(1).getBalanceBefore()).isEqualByComparingTo(chain.get(0).getBalanceAfter());
        WalletTransactionState s1 = walletTxStateRepository.findByWalletTransactionId(t1.getId()).orElseThrow();
        assertThat(s1.getStatus()).isEqualTo(WalletTransactionStatus.SUCCESS);
        assertThat(s1.getProcessedAt()).isNotNull();
        assertThat(s1.getMetadata()).containsEntry("k", "v");
    }

    /**
     * FIXED DJANGO BUG (PROGRESS.md payment open question #2): in Django the integrity check never
     * passed for a wallet with activity (scale mismatch between write-time and read-time Decimal
     * strings) nor for a brand-new wallet (empty signature). Both now verify, and tampering is
     * still detected by every layer.
     */
    @Test
    void integrity_passesForNewAndActiveWallets_andStillDetectsTampering() throws Exception {
        Account fresh = register("fresh", UserRole.CUSTOMER);
        InternalWallet newWallet = walletService.getOrCreateWallet(fresh.user().getId());
        assertThat(newWallet.getSignature()).isNotEmpty(); // signed at creation
        assertThat(walletRepository.findByUserId(fresh.user().getId()).orElseThrow().getSignature())
                .isEqualTo(newWallet.getSignature());
        WalletService.IntegrityReport empty = walletService.verifyIntegrity(newWallet);
        assertThat(empty.signatureValid()).isTrue();
        assertThat(empty.balanceMatchesLedger()).isTrue();
        assertThat(empty.chainIntact()).isTrue();

        Account active = register("active", UserRole.CUSTOMER);
        walletService.credit(active.user().getId(), new BigDecimal("180000"), WalletTransactionType.RELEASE, null, "r", "d", null);
        walletService.credit(active.user().getId(), new BigDecimal("20000"), WalletTransactionType.REFUND, null, "r2", "d", null);
        InternalWallet wallet = walletService.getOrCreateWallet(active.user().getId());
        WalletService.IntegrityReport report = walletService.verifyIntegrity(wallet);
        assertThat(report.balanceMatchesLedger()).isTrue();
        assertThat(report.signatureValid()).isTrue();
        assertThat(report.chainIntact()).isTrue();
        assertThat(report.chainLength()).isEqualTo(2);

        // tampering with the balance directly is detected by both the signature and the ledger sum
        jdbc.update("UPDATE internal_wallets SET balance = 990000 WHERE user_id = ?", active.user().getId());
        WalletService.IntegrityReport tampered = walletService.verifyIntegrity(walletService.getOrCreateWallet(active.user().getId()));
        assertThat(tampered.signatureValid()).isFalse();
        assertThat(tampered.balanceMatchesLedger()).isFalse();
    }

    @Test
    void credits_rejectNonPositiveAmounts_beforeWritingAnything() throws Exception {
        Account user = register("zero", UserRole.CUSTOMER);
        assertThatThrownBy(() -> walletService.credit(user.user().getId(), BigDecimal.ZERO, WalletTransactionType.REFUND,
                null, null, null, null)).isInstanceOf(PaymentValueError.class).hasMessage("Amount must be greater than zero");
        assertThatThrownBy(() -> walletService.credit(user.user().getId(), new BigDecimal("0.4"), WalletTransactionType.REFUND,
                null, null, null, null)).isInstanceOf(PaymentValueError.class);
        assertThat(walletRepository.findByUserId(user.user().getId())).isEmpty();
    }

    @Test
    void concurrentCredits_serializeOnTheWalletRowLock_noLostUpdate_chainStaysLinear() throws Exception {
        Account user = register("race", UserRole.CUSTOMER);
        Long id = user.user().getId();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return walletService.credit(id, new BigDecimal("1000"), WalletTransactionType.REFUND, null, "r", "d", null);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(walletRepository.findByUserId(id).orElseThrow().getBalance()).isEqualByComparingTo("10000");
        List<WalletTransaction> chain = chainOf(id);
        assertThat(chain).hasSize(threads);
        String expectedPrev = WalletTransaction.GENESIS_HASH;
        BigDecimal expectedBefore = BigDecimal.ZERO;
        for (WalletTransaction t : chain) {
            assertThat(t.getPreviousHash()).isEqualTo(expectedPrev);           // no fork
            assertThat(t.getBalanceBefore()).isEqualByComparingTo(expectedBefore); // no lost update
            expectedPrev = t.getChainHash();
            expectedBefore = t.getBalanceAfter();
        }
        assertThat(walletService.computeExpectedBalance(id)).isEqualByComparingTo("10000");
    }

    @Test
    void walletTransactions_areAppendOnly_atTheDatabaseLevel() throws Exception {
        Account user = register("immutable", UserRole.CUSTOMER);
        WalletTransaction t = walletService.credit(user.user().getId(), new BigDecimal("5000"),
                WalletTransactionType.REFUND, null, "r", "d", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE wallet_transactions SET amount = 999999 WHERE id = ?", t.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM wallet_transactions WHERE id = ?", t.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(walletTransactionRepository.findById(t.getId()).orElseThrow().getAmount()).isEqualByComparingTo("5000");
    }

    // ------------------------------------------------------------------ withdrawals (PAYOUT)

    @Test
    void withdraw_success_movesMoneyOut_throughASignedPayosPayout() throws Exception {
        Account user = register("wd", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "100000");

        Map<String, Object> r = withdrawalService.withdrawInternalWallet(user.user(), new BigDecimal("30000"));

        assertThat(r).containsEntry("success", true).containsEntry("status", "SUCCESS")
                .containsEntry("amount", 30000.0).containsEntry("bank_account", "Vietcombank - ******6789");
        InternalWallet wallet = walletRepository.findByUserId(user.user().getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("70000");
        assertThat(wallet.getPendingBalance()).isEqualByComparingTo("0");
        WalletTransaction payout = chainOf(user.user().getId()).getLast();
        assertThat(payout.getTransactionType()).isEqualTo(WalletTransactionType.PAYOUT);
        assertThat(payout.getBalanceBefore()).isEqualByComparingTo("100000");
        assertThat(payout.getBalanceAfter()).isEqualByComparingTo("70000");
        WalletTransactionState state = walletTxStateRepository.findByWalletTransactionId(payout.getId()).orElseThrow();
        assertThat(state.getStatus()).isEqualTo(WalletTransactionStatus.SUCCESS);
        assertThat(state.getPayoutId()).isEqualTo(r.get("payout_id"));
        assertThat(state.getMetadata()).containsKey("payout_result").containsEntry("bank_account_number", "0123456789");
        assertThat(walletService.computeExpectedBalance(user.user().getId())).isEqualByComparingTo("70000");

        var sent = fakePayOs.requestsTo("/v1/payouts").getFirst();
        assertThat(sent.body()).contains("\"amount\":30000").contains("\"toBin\":\"970436\"")
                .contains("\"toAccountNumber\":\"0123456789\"");
        assertThat(sent.headers()).containsKey("x-idempotency-key");

        // FIXED (open question #2): the success path re-signs the wallet -> it still verifies, and a
        // second withdrawal goes through (Django left the signature stale and blocked it forever)
        WalletService.IntegrityReport after = walletService.verifyIntegrity(walletService.getOrCreateWallet(user.user().getId()));
        assertThat(after.signatureValid()).isTrue();
        assertThat(after.balanceMatchesLedger()).isTrue();
        assertThat(after.chainIntact()).isTrue();
        Map<String, Object> second = withdrawalService.withdrawInternalWallet(user.user(), new BigDecimal("10000"));
        assertThat(second).containsEntry("success", true).containsEntry("status", "SUCCESS");
        assertThat(walletRepository.findByUserId(user.user().getId()).orElseThrow().getBalance()).isEqualByComparingTo("60000");
        WalletService.IntegrityReport afterSecond = walletService.verifyIntegrity(walletService.getOrCreateWallet(user.user().getId()));
        assertThat(afterSecond.signatureValid()).isTrue();
        assertThat(afterSecond.balanceMatchesLedger()).isTrue();
        assertThat(afterSecond.chainIntact()).isTrue();
        assertThat(afterSecond.chainLength()).isEqualTo(3);
    }

    @Test
    void withdraw_payoutRejected_revertsTheDebitInTheSameTransaction_andLogsTheFailure() throws Exception {
        Account user = register("wdfail", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "100000");
        fakePayOs.setPayoutMode("http400");

        Map<String, Object> r = withdrawalService.withdrawInternalWallet(user.user(), new BigDecimal("30000"));

        assertThat(r).containsEntry("success", false).containsEntry("status", "FAILED")
                .containsEntry("error", "Tai khoan khong hop le");
        assertThat(fakePayOs.payoutCalls()).isEqualTo(1); // 4xx: not retried at either layer
        InternalWallet wallet = walletRepository.findByUserId(user.user().getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("100000");
        assertThat(wallet.getPendingBalance()).isEqualByComparingTo("0");
        WalletTransaction payout = chainOf(user.user().getId()).getLast();
        assertThat(payout.getTransactionType()).isEqualTo(WalletTransactionType.PAYOUT);
        assertThat(walletTxStateRepository.findByWalletTransactionId(payout.getId()).orElseThrow().getStatus())
                .isEqualTo(WalletTransactionStatus.FAILED);
        // a FAILED payout is not a debit for the ledger sum
        assertThat(walletService.computeExpectedBalance(user.user().getId())).isEqualByComparingTo("100000");
        List<WithdrawalFailureLog> logs = failureLogRepository.findByUserIdOrderByCreatedAtAscIdAsc(user.user().getId());
        assertThat(logs).singleElement().satisfies(l -> {
            assertThat(l.getStage()).isEqualTo("PAYOUT_FAILED");
            assertThat(l.getWalletTransactionId()).isEqualTo(payout.getId());
            assertThat(l.getMetadata()).containsEntry("bank_account", "******6789");
        });
    }

    @Test
    void withdraw_transientGatewayErrors_areRetried_thenRevertedCleanly() throws Exception {
        Account user = register("wd500", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "50000");
        fakePayOs.setPayoutMode("http500");

        Map<String, Object> r = withdrawalService.withdrawInternalWallet(user.user(), new BigDecimal("20000"));

        assertThat(r).containsEntry("success", false);
        assertThat(fakePayOs.payoutCalls()).isEqualTo(9); // 3 service attempts x (1 + 2 SDK retries), same idempotency key
        assertThat(fakePayOs.requestsTo("/v1/payouts").stream().map(x -> x.headers().get("x-idempotency-key")).distinct())
                .hasSize(1);
        assertThat(walletRepository.findByUserId(user.user().getId()).orElseThrow().getBalance()).isEqualByComparingTo("50000");
    }

    @Test
    void withdraw_moreThanTheBalance_isRefused_andWritesNothing() throws Exception {
        Account user = register("wdpoor", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "10000");
        Map<String, Object> r = withdrawalService.withdrawInternalWallet(user.user(), new BigDecimal("20000"));
        assertThat(r).containsEntry("success", false).containsEntry("error", "Insufficient internal wallet balance");
        assertThat(chainOf(user.user().getId())).hasSize(1);
        assertThat(fakePayOs.payoutCalls()).isZero();
        assertThat(walletRepository.findByUserId(user.user().getId()).orElseThrow().getBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void withdraw_withoutUsableBankInfo_isRefused() throws Exception {
        Account none = register("wdnobank", UserRole.CUSTOMER);
        assertThat(withdrawalService.withdrawInternalWallet(none.user(), new BigDecimal("20000")))
                .containsEntry("error", "Bank information not found. Please add bank information first.");
        Account unverified = register("wdunv", UserRole.CUSTOMER);
        customerPaymentInfoRepository.save(CustomerPaymentInfo.builder().userId(unverified.user().getId())
                .bankName("VCB").bankCode("970436").bankAccountNumber("0123456789").bankAccountName("A")
                .verified(false).build());
        assertThat(withdrawalService.withdrawInternalWallet(unverified.user(), new BigDecimal("20000")))
                .containsEntry("error", "Bank information is not verified");
    }

    @Test
    void concurrentWithdrawals_canNeverSpendTheSameMoneyTwice() throws Exception {
        Account user = register("wdrace", UserRole.CUSTOMER);
        verifiedBank(user);
        seedIntegralWallet(user.user(), "100000");
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Map<String, Object>> task = () -> {
                start.await();
                return withdrawalService.withdrawInternalWallet(user.user(), new BigDecimal("60000"));
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Map<String, Object>> f : futures) {
            if (Boolean.TRUE.equals(f.get().get("success"))) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(1);
        InternalWallet wallet = walletRepository.findByUserId(user.user().getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("40000");
        assertThat(wallet.getBalance().signum()).isGreaterThanOrEqualTo(0);
        assertThat(wallet.getPendingBalance()).isEqualByComparingTo("0");
        assertThat(fakePayOs.payoutCalls()).isEqualTo(1);
        assertThat(chainOf(user.user().getId())).filteredOn(t -> t.getTransactionType() == WalletTransactionType.PAYOUT)
                .hasSize(1);
    }
}
