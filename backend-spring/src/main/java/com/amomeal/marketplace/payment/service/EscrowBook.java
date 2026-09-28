package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.repository.PaymentOrderRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-order escrow bookkeeping for a checkout's single PayOS payment — the fix for payment open
 * question #3 (multi-chef checkouts), built entirely on data Django already writes (see the
 * Part A findings in PROGRESS.md; no migration):
 * <ul>
 *   <li>"has THIS order been released to its chef / refunded to the customer?" = does a
 *       RELEASE / REFUND {@code wallet_transactions} row with this {@code order_uid} exist?
 *       Both credits already carry the order (Django {@code credit_internal_wallet(order=...)});
 *       the table is append-only and each credit row is SUCCESS from insert.</li>
 *   <li>the checkout-level payment state only describes the AGGREGATE: it stays HOLDING while any
 *       order still has money in escrow and moves to RELEASED/REFUNDED when the last one is
 *       resolved ({@link #siblings}).</li>
 * </ul>
 * Callers must hold {@code SELECT ... FOR UPDATE} on the payment row while they read these and
 * write the matching credit, so per-order decisions are serialized per payment (exactly-once).
 */
@Component
@RequiredArgsConstructor
class EscrowBook {

    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentOrderRepository orderRepository;

    boolean isReleased(UUID orderUid) {
        return walletTransactionRepository.existsByOrderUidAndTransactionType(orderUid, WalletTransactionType.RELEASE);
    }

    boolean isRefunded(UUID orderUid) {
        return walletTransactionRepository.existsByOrderUidAndTransactionType(orderUid, WalletTransactionType.REFUND);
    }

    /**
     * @param allResolved every OTHER order of the checkout is out of escrow — released, refunded,
     *                    or terminal ({@code COMPLETED}/{@code CANCELLED}: covers an ownerless
     *                    order whose refund credits nobody, and a failed refund that the order
     *                    module cancelled anyway, so the aggregate state never sticks at HOLDING)
     * @param anyReleased some OTHER order of the checkout was released to its chef
     */
    record Siblings(boolean allResolved, boolean anyReleased) {
    }

    /** The escrow status of every order of {@code checkoutUid} except {@code orderUid}. */
    Siblings siblings(UUID checkoutUid, UUID orderUid) {
        Map<UUID, OrderStatus> others = orderRepository.findUidAndStatusOfCheckout(checkoutUid).stream()
                .filter(row -> !orderUid.equals(row[0]))
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (OrderStatus) row[1]));
        if (others.isEmpty()) {
            return new Siblings(true, false);
        }
        Set<UUID> released = new HashSet<>(walletTransactionRepository.findOrderUidsHavingType(others.keySet(),
                List.of(WalletTransactionType.RELEASE)));
        Set<UUID> refunded = new HashSet<>(walletTransactionRepository.findOrderUidsHavingType(others.keySet(),
                List.of(WalletTransactionType.REFUND)));
        boolean allResolved = others.entrySet().stream().allMatch(e -> released.contains(e.getKey())
                || refunded.contains(e.getKey())
                || e.getValue() == OrderStatus.COMPLETED || e.getValue() == OrderStatus.CANCELLED);
        return new Siblings(allResolved, !released.isEmpty());
    }
}
