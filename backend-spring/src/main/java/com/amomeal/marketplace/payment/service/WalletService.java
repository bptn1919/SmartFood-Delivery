package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.payment.dto.InternalWalletSummaryResponse;
import com.amomeal.marketplace.payment.dto.WalletTransactionResponse;
import com.amomeal.marketplace.payment.entity.InternalWallet;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionState;
import com.amomeal.marketplace.payment.entity.WalletTransactionStatus;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.provider.PayOsSignature;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.InternalWalletRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionStateRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of the internal-wallet ledger of ../../backend/payment: {@code InternalWallet}'s
 * signature/integrity methods (models.py) and {@code PaymentService.credit_internal_wallet},
 * {@code get_or_create_internal_wallet}, {@code _quantize_amount} (services.py).
 *
 * <h2>Locking (mirrors Django's {@code select_for_update()} exactly)</h2>
 * Every balance mutation happens inside one transaction that first takes
 * {@code SELECT ... FOR UPDATE} on the user's {@code internal_wallets} row
 * ({@link #lockOrCreate}) — so two concurrent credits/withdrawals serialize, each reads the
 * other's committed balance, and the chain's "previous hash" is read under the same lock.
 *
 * <h2>The string forms the HMACs are computed over — FIXED (payment open question #2)</h2>
 * Django formatted Decimals with {@code str()}, which keeps the value's scale: at write time the
 * new balance was {@code "180000"}, at verify time the NUMERIC(15,2) column read back
 * {@code "180000.00"}, and a new wallet had an empty signature — so {@code verify_integrity()}
 * never passed and no withdrawal could ever happen. The user chose "fix" (2026-09-23): every
 * amount is now hashed/signed as {@link PaymentHmac#canonicalAmount} (2 dp, plain), new wallets
 * are signed at creation ({@link #signIfCreated}), and every balance-changing operation re-signs
 * (credit here; debit, revert AND the successful payout in {@link WithdrawalService}). Fresh DB,
 * so there are no legacy hashes to migrate.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final InternalWalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletTransactionStateRepository walletTransactionStateRepository;
    private final PaymentHmac hmac;
    private final PaymentTx tx;
    private final EntityManager entityManager;

    // =====================================================================
    // helpers
    // =====================================================================

    /** Django {@code _quantize_amount}: {@code Decimal(str(amount)).quantize(Decimal("1"), ROUND_HALF_UP)}. */
    public static BigDecimal quantize(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    /** Django {@code InternalWallet.compute_signature()} over the wallet's CURRENT in-memory values. */
    public String computeSignature(InternalWallet wallet) {
        return hmac.walletSignature(wallet.getUserId(), wallet.getBalance(), wallet.getPendingBalance());
    }

    /** Django {@code verify_signature()}: empty → False, else {@code hmac.compare_digest}. */
    public boolean verifySignature(InternalWallet wallet) {
        if (wallet.getSignature() == null || wallet.getSignature().isEmpty()) {
            return false;
        }
        return PayOsSignature.constantTimeEquals(computeSignature(wallet), wallet.getSignature());
    }

    // =====================================================================
    // get_or_create
    // =====================================================================

    /** Django {@code get_or_create_internal_wallet(user)} — no lock. */
    public InternalWallet getOrCreateWallet(Long userId) {
        return tx.required(() -> {
            boolean created = walletRepository.insertIfAbsent(userId) > 0;
            InternalWallet wallet = walletRepository.findByUserId(userId).orElseThrow();
            entityManager.refresh(wallet);
            signIfCreated(wallet, created);
            return wallet;
        });
    }

    /**
     * Django {@code InternalWallet.objects.select_for_update().get_or_create(user=user)[0]}.
     * MUST be called inside a transaction (the lock lives until it ends).
     *
     * <p>The row is re-read from the database after locking ({@code refresh}), because Django
     * gets a fresh instance from the SELECT — a cached entity from earlier in this persistence
     * context would carry stale (and differently scaled) values.
     */
    InternalWallet lockOrCreate(Long userId) {
        boolean created = walletRepository.insertIfAbsent(userId) > 0;
        InternalWallet wallet = walletRepository.findForUpdateByUserId(userId).orElseThrow();
        entityManager.refresh(wallet);
        signIfCreated(wallet, created);
        return wallet;
    }

    /**
     * Fix for open question #2: a wallet this call just inserted (signature {@code ""}, which
     * Django's {@code verify_signature} treats as invalid) is signed immediately over its
     * zero balances, so a brand-new wallet verifies.
     */
    private void signIfCreated(InternalWallet wallet, boolean created) {
        if (created) {
            wallet.setSignature(computeSignature(wallet));
            walletRepository.saveAndFlush(wallet);
        }
    }

    /**
     * Django {@code get_internal_wallet_summary(user)}: the wallet (created if missing), plus the
     * 10 most recent ledger lines with their state ({@code status} defaults to PENDING when a
     * line has no state row).
     */
    public InternalWalletSummaryResponse getInternalWalletSummary(Long userId) {
        InternalWallet wallet = getOrCreateWallet(userId);
        return tx.required(() -> {
            List<WalletTransaction> recent = walletTransactionRepository.findTop10ByUserIdOrderByCreatedAtDescIdDesc(userId);
            Map<Long, WalletTransactionState> states = new HashMap<>();
            if (!recent.isEmpty()) {
                for (WalletTransactionState s : walletTransactionStateRepository.findByWalletTransactionIdIn(
                        recent.stream().map(WalletTransaction::getId).toList())) {
                    states.put(s.getWalletTransactionId(), s);
                }
            }
            List<WalletTransactionResponse> lines = recent.stream().map(t -> {
                WalletTransactionState s = states.get(t.getId());
                return new WalletTransactionResponse(t.getUid(), t.getTransactionType().name(),
                        s != null ? s.getStatus().name() : WalletTransactionStatus.PENDING.name(),
                        t.getAmount(), t.getReferenceId(), s != null ? s.getDescription() : null,
                        t.getBalanceBefore(), t.getBalanceAfter(), s != null ? s.getPayoutId() : null,
                        t.getOrderUid(), t.getCreatedAt(), s != null ? s.getProcessedAt() : null);
            }).toList();
            return new InternalWalletSummaryResponse(null, userId, wallet.getBalance(), wallet.getPendingBalance(),
                    wallet.getBalance().add(wallet.getPendingBalance()), wallet.getCurrency(), lines);
        });
    }

    // =====================================================================
    // credit_internal_wallet
    // =====================================================================

    /**
     * Django {@code credit_internal_wallet(user, amount, transaction_type, order, reference_id,
     * description, metadata)} — RELEASE (escrow → chef on order completion) and REFUND
     * (escrow → customer on cancellation) both come through here.
     *
     * <p>One transaction (joins the caller's, like Django's nested {@code atomic}): lock wallet →
     * {@code balance += amount} → re-sign → append a {@link WalletTransaction} chained to the
     * user's previous one → its {@link WalletTransactionState} is SUCCESS immediately.
     * A credit can never make a balance negative ({@code amount > 0} is enforced first).
     *
     * @throws PaymentValueError "Amount must be greater than zero" (Django ValueError, raised
     *         before any write)
     */
    public WalletTransaction credit(Long userId, BigDecimal rawAmount, WalletTransactionType type, UUID orderUid,
                                    String referenceId, String description, Map<String, Object> metadata) {
        BigDecimal amount = quantize(rawAmount);
        if (amount.signum() <= 0) {
            throw new PaymentValueError("Amount must be greater than zero");
        }
        return tx.required(() -> {
            InternalWallet wallet = lockOrCreate(userId);
            BigDecimal balanceBefore = wallet.getBalance();
            wallet.setBalance(quantize(balanceBefore.add(amount)));

            UUID txUid = UUID.randomUUID();
            String previousHash = lastChainHash(userId);
            String chainHash = hmac.walletChainHash(txUid, userId, type.name(), amount, balanceBefore,
                    wallet.getBalance(), previousHash);

            wallet.setSignature(computeSignature(wallet));
            walletRepository.saveAndFlush(wallet);

            WalletTransaction ledger = walletTransactionRepository.saveAndFlush(WalletTransaction.builder()
                    .uid(txUid)
                    .userId(userId)
                    .orderUid(orderUid)
                    .transactionType(type)
                    .amount(amount)
                    .referenceId(referenceId)
                    .balanceBefore(balanceBefore)
                    .balanceAfter(wallet.getBalance())
                    .previousHash(previousHash)
                    .chainHash(chainHash)
                    .build());
            walletTransactionStateRepository.saveAndFlush(WalletTransactionState.builder()
                    .walletTransactionId(ledger.getId())
                    .status(WalletTransactionStatus.SUCCESS)
                    .description(description == null ? "" : description)
                    .metadata(metadata)
                    .processedAt(Instant.now())
                    .build());
            return ledger;
        });
    }

    /** Django {@code WalletTransaction.get_last_chain_hash(user_id)}. */
    String lastChainHash(Long userId) {
        return walletTransactionRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .map(WalletTransaction::getChainHash)
                .filter(h -> !h.isEmpty())
                .orElse(WalletTransaction.GENESIS_HASH);
    }

    // =====================================================================
    // verify_integrity
    // =====================================================================

    public record ChainResult(boolean chainIntact, int chainLength, String breakAtUid, String breakReason) {
    }

    public record IntegrityReport(boolean signatureValid, boolean balanceMatchesLedger, boolean chainIntact,
                                  String storedBalance, String expectedBalance, int chainLength,
                                  String chainBreakAt, String chainBreakReason) {
    }

    /** Django {@code InternalWallet.compute_expected_balance()}: SUCCESS (REFUND+RELEASE) − SUCCESS PAYOUT. */
    public BigDecimal computeExpectedBalance(Long userId) {
        return tx.required(() -> {
            BigDecimal credits = walletTransactionRepository.sumAmount(userId,
                    List.of(WalletTransactionType.REFUND, WalletTransactionType.RELEASE), WalletTransactionStatus.SUCCESS);
            BigDecimal debits = walletTransactionRepository.sumAmount(userId,
                    List.of(WalletTransactionType.PAYOUT), WalletTransactionStatus.SUCCESS);
            return credits.subtract(debits);
        });
    }

    /**
     * Django {@code InternalWallet._verify_wallet_tx_chain()}: walk the user's ledger oldest
     * first; for each row check (2) linkage to the previous chain hash, (1) its own hash over
     * its stored values, (3) {@code balance_before[n] == balance_after[n-1]}.
     */
    public ChainResult verifyWalletTxChain(Long userId) {
        List<WalletTransaction> txs = tx.required(() -> walletTransactionRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId));
        if (txs.isEmpty()) {
            return new ChainResult(true, 0, null, null);
        }
        String expectedPrevious = WalletTransaction.GENESIS_HASH;
        for (int i = 0; i < txs.size(); i++) {
            WalletTransaction t = txs.get(i);
            String uid = t.getUid().toString();
            if (!PayOsSignature.constantTimeEquals(t.getPreviousHash(), expectedPrevious)) {
                return new ChainResult(false, i, uid, "chain_link_broken");
            }
            String expectedHash = hmac.walletChainHash(t.getUid(), t.getUserId(), t.getTransactionType().name(),
                    t.getAmount(), t.getBalanceBefore(), t.getBalanceAfter(), t.getPreviousHash());
            if (!PayOsSignature.constantTimeEquals(expectedHash, t.getChainHash())) {
                return new ChainResult(false, i, uid, "hash_mismatch");
            }
            if (i > 0 && t.getBalanceBefore().compareTo(txs.get(i - 1).getBalanceAfter()) != 0) {
                return new ChainResult(false, i, uid, "balance_discontinuity");
            }
            expectedPrevious = t.getChainHash();
        }
        return new ChainResult(true, txs.size(), null, null);
    }

    /** Django {@code InternalWallet.verify_integrity()} — all three must be true to withdraw. */
    public IntegrityReport verifyIntegrity(InternalWallet wallet) {
        boolean sigOk = verifySignature(wallet);
        BigDecimal expected = computeExpectedBalance(wallet.getUserId());
        boolean balanceOk = wallet.getBalance().compareTo(expected) == 0; // Decimal ==: scale-insensitive
        ChainResult chain = verifyWalletTxChain(wallet.getUserId());
        return new IntegrityReport(sigOk, balanceOk, chain.chainIntact(), PyCompat.decimal(wallet.getBalance()),
                PyCompat.decimal(expected), chain.chainLength(), chain.breakAtUid(), chain.breakReason());
    }
}
