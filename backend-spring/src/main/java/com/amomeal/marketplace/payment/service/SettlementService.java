package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.payment.entity.ChefCodBalance;
import com.amomeal.marketplace.payment.entity.InternalWallet;
import com.amomeal.marketplace.payment.entity.LedgerType;
import com.amomeal.marketplace.payment.entity.PayoutLedger;
import com.amomeal.marketplace.payment.entity.SettlementRecord;
import com.amomeal.marketplace.payment.entity.SettlementStatus;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.ChefCodBalanceRepository;
import com.amomeal.marketplace.payment.repository.InternalWalletRepository;
import com.amomeal.marketplace.payment.repository.PaymentOrderRepository;
import com.amomeal.marketplace.payment.repository.PayoutLedgerRepository;
import com.amomeal.marketplace.payment.repository.SettlementRecordRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port of the settlement half of ../../backend/payment/services.py::PaymentService:
 * {@code create_settlement_record}, {@code update_chef_cod_balance},
 * {@code settle_cod_balance_for_chef}, {@code get_chef_balance_summary}.
 *
 * <p>A settlement is BOOKKEEPING: it records how a completed order's gross amount splits into
 * the platform's 10% and the chef's 90% (double entry: PLATFORM_REVENUE + CHEF_PAYOUT). The
 * money itself reaches the chef through the separate {@code credit_internal_wallet(RELEASE)}
 * that {@code complete_order_with_release} performs right after (see
 * {@code PaymentOrderPaymentGateway#settleCompletedOrder}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final PaymentOrderRepository orderRepository;
    private final SettlementRecordRepository settlementRepository;
    private final PayoutLedgerRepository ledgerRepository;
    private final ChefCodBalanceRepository codBalanceRepository;
    private final InternalWalletRepository walletRepository;
    private final PaymentTx tx;

    /**
     * Django {@code create_settlement_record(order_uid, chef_user)} — one atomic block:
     * {@code gross = q(order.total_price)}, {@code fee = q(gross * 0.10)},
     * {@code chef = q(gross - fee)} (q = HALF_UP to whole VND), a LEDGER_RECORDED settlement
     * and its two ledger lines. PORT-NOTE (preserved): no idempotency check — a second call for
     * the same order violates the one-settlement-per-order unique constraint (unreachable via
     * the order state machine, which only lets an order reach COMPLETED once).
     */
    public SettlementRecord createSettlementRecord(UUID orderUid, CustomUser chef) {
        return tx.required(() -> {
            Order order = orderRepository.findWithCheckout(orderUid)
                    .orElseThrow(() -> new IllegalStateException("Order matching query does not exist."));
            BigDecimal gross = WalletService.quantize(order.getTotalPrice() != null ? order.getTotalPrice() : BigDecimal.ZERO);
            BigDecimal platformFee = WalletService.quantize(gross.multiply(PaymentService.PLATFORM_FEE_PERCENT));
            BigDecimal chefPayout = WalletService.quantize(gross.subtract(platformFee));
            PaymentMethod method = order.getCheckout().getPaymentMethod();
            Long chefId = chef != null ? chef.getId() : null;

            Map<String, Object> settlementData = new LinkedHashMap<>();
            settlementData.put("order_total", gross.doubleValue());
            settlementData.put("payment_method", method.name());
            settlementData.put("settled_at", PyCompat.isoformat(Instant.now()));
            SettlementRecord settlement = settlementRepository.saveAndFlush(SettlementRecord.builder()
                    .orderUid(orderUid)
                    .chefId(chefId)
                    .grossAmount(gross)
                    .platformFee(platformFee)
                    .chefPayoutAmount(chefPayout)
                    .paymentMethod(method)
                    .status(SettlementStatus.LEDGER_RECORDED)
                    .settlementData(settlementData)
                    .build());
            ledgerRepository.saveAndFlush(PayoutLedger.builder()
                    .settlementRecordId(settlement.getId())
                    .ledgerType(LedgerType.PLATFORM_REVENUE)
                    .amount(platformFee)
                    .description("Platform commission for order " + orderUid)
                    .orderUid(orderUid.toString())
                    .chefId(chefId)
                    .build());
            ledgerRepository.saveAndFlush(PayoutLedger.builder()
                    .settlementRecordId(settlement.getId())
                    .ledgerType(LedgerType.CHEF_PAYOUT)
                    .amount(chefPayout)
                    .description("Payout for order " + orderUid)
                    .orderUid(orderUid.toString())
                    .chefId(chefId)
                    .build());
            log.info("[Settlement] Created settlement record {} for order {} (gross {}, fee {}, chef {})",
                    settlement.getUid(), orderUid, gross, platformFee, chefPayout);
            return settlement;
        });
    }

    /**
     * Django {@code update_chef_cod_balance(chef_user, amount)}. PORT-NOTE: dead code in Django —
     * nothing calls it (the COD completion path only sets the payment SUCCESS), so
     * {@code chef_cod_balance} rows never exist in practice. Kept (public) because
     * {@link #settleCodBalanceForChef} is live and meaningless without it.
     */
    public void updateChefCodBalance(Long chefId, BigDecimal amount) {
        tx.required(() -> {
            ChefCodBalance balance = codBalanceRepository.findByChefId(chefId)
                    .orElseGet(() -> ChefCodBalance.builder().chefId(chefId).build());
            balance.setUnsettledBalance(WalletService.quantize(balance.getUnsettledBalance().add(amount)));
            balance.setUnsettledOrdersCount(balance.getUnsettledOrdersCount() + 1);
            codBalanceRepository.saveAndFlush(balance);
        });
    }

    /**
     * Django {@code settle_cod_balance_for_chef(chef_user)} — under
     * {@code select_for_update()} of the chef's COD balance: sum the chef payouts of COD
     * settlements still LEDGER_RECORDED/PAYOUT_SCHEDULED, flip them to PAYOUT_SCHEDULED, move
     * {@code unsettled_balance} into {@code total_settled}. No money is transferred.
     * Never throws (Django's {@code except Exception} → failure dict).
     */
    public Map<String, Object> settleCodBalanceForChef(CustomUser chef) {
        try {
            return tx.required(() -> {
                Optional<ChefCodBalance> locked = codBalanceRepository.findForUpdateByChefId(chef.getId());
                if (locked.isEmpty()) {
                    return PaymentRefundService.result("success", false, "error", "COD balance not found for chef");
                }
                ChefCodBalance balance = locked.get();
                if (balance.getUnsettledBalance().signum() <= 0) {
                    return PaymentRefundService.result("success", false, "error", "No unsettled COD balance available");
                }
                List<SettlementRecord> unsettled = settlementRepository.findByChefIdAndPaymentMethodAndStatusIn(
                        chef.getId(), PaymentMethod.COD,
                        List.of(SettlementStatus.LEDGER_RECORDED, SettlementStatus.PAYOUT_SCHEDULED));
                BigDecimal total = unsettled.stream().map(SettlementRecord::getChefPayoutAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!unsettled.isEmpty()) {
                    settlementRepository.updateStatus(unsettled.stream().map(SettlementRecord::getId).toList(),
                            SettlementStatus.PAYOUT_SCHEDULED);
                }
                ChefCodBalance fresh = codBalanceRepository.findByChefId(chef.getId()).orElseThrow();
                fresh.setTotalSettled(WalletService.quantize(fresh.getTotalSettled().add(fresh.getUnsettledBalance())));
                fresh.setUnsettledBalance(BigDecimal.ZERO);
                fresh.setUnsettledOrdersCount(0);
                fresh.setLastSettlementAt(Instant.now());
                codBalanceRepository.saveAndFlush(fresh);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", true);
                out.put("chef_id", chef.getId());
                out.put("chef_email", chef.getEmail());
                out.put("settled_amount", total.doubleValue());
                out.put("order_count", unsettled.size());
                out.put("settled_at", PyCompat.isoformat(Instant.now()));
                return out;
            });
        } catch (RuntimeException ex) {
            log.error("[COD Settlement] Error settling balance: {}", ex.toString());
            return PaymentRefundService.result("success", false, "error", String.valueOf(ex.getMessage()));
        }
    }

    public record ChefBalanceSummary(Long chefId, String chefEmail, BigDecimal codUnsettledBalance, int codUnsettledOrders,
                                     BigDecimal walletBalance, BigDecimal totalAvailablePayout, BigDecimal totalSettled) {
    }

    /**
     * Django {@code get_chef_balance_summary(chef_user)}. PORT-NOTE (preserved):
     * {@code total_settled = SUM(LEDGER_RECORDED settlements' chef_payout) + wallet balance} —
     * for PayOS the same money is counted twice (once as a LEDGER_RECORDED settlement, once as
     * the RELEASE credit already in the wallet). Reporting-only; no money moves on it.
     */
    public ChefBalanceSummary getChefBalanceSummary(CustomUser chef) {
        return tx.required(() -> {
            Optional<ChefCodBalance> cod = codBalanceRepository.findByChefId(chef.getId());
            BigDecimal codBalance = cod.map(ChefCodBalance::getUnsettledBalance).orElse(BigDecimal.ZERO);
            int unsettledOrders = cod.map(ChefCodBalance::getUnsettledOrdersCount).orElse(0);
            BigDecimal walletBalance = walletRepository.findByUserId(chef.getId())
                    .map(InternalWallet::getBalance).orElse(BigDecimal.ZERO);
            BigDecimal completed = settlementRepository.sumChefPayout(chef.getId(), SettlementStatus.LEDGER_RECORDED);
            BigDecimal completedTotal = completed != null ? completed : BigDecimal.ZERO;
            return new ChefBalanceSummary(chef.getId(), chef.getEmail(), codBalance, unsettledOrders, walletBalance,
                    codBalance.add(walletBalance), completedTotal.add(walletBalance));
        });
    }
}
