package com.amomeal.marketplace.payment.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Programmatic transaction boundaries for the payment module — the tool that lets this port
 * reproduce Django's transaction semantics exactly (CLAUDE.md §8b).
 *
 * <p><b>Why programmatic.</b> Django's {@code payment/services.py} mixes three regimes inside
 * single methods: plain autocommit (each {@code .save()}/{@code .update()} commits on its own —
 * the webhook, sync, create-payment and withdraw-OTP paths), explicit
 * {@code with transaction.atomic():} blocks (wallet credit/withdraw, refund, settlement), and
 * "whatever the caller has" (when {@code order}'s {@code @transaction.atomic} methods call in).
 * A {@link TransactionTemplate} per step expresses that without a bean per step (a
 * {@code @Transactional} method called from its own class is self-invocation and silently
 * ignored — CLAUDE.md §8b).
 *
 * <ul>
 *   <li>{@link #required} — one Django statement / atomic block: joins the caller's
 *       transaction if there is one (Django: inside the caller's {@code atomic}), else commits on
 *       its own (Django: autocommit). <b>Code inside must not throw after writing</b> if the
 *       write is meant to survive (Django's "save then raise" under autocommit): return a
 *       marker and throw after the template returns — a callback that throws marks a joined
 *       outer transaction rollback-only.</li>
 *   <li>{@link #requiresNew} — a block that must commit independently of the caller (used for
 *       the refund — see {@code PaymentRefundService}).</li>
 *   <li>{@link #notSupported} — run an autocommit-style orchestration with any caller
 *       transaction SUSPENDED, so each inner {@link #required} step really commits on its own
 *       (e.g. the order module's read-only transaction calling the gateway's payment sync).</li>
 * </ul>
 */
@Component
public class PaymentTx {

    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate notSupported;

    public PaymentTx(PlatformTransactionManager transactionManager) {
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.notSupported = new TransactionTemplate(transactionManager);
        this.notSupported.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public <T> T required(Supplier<T> work) {
        return required.execute(status -> work.get());
    }

    public void required(Runnable work) {
        required.executeWithoutResult(status -> work.run());
    }

    public <T> T requiresNew(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    public <T> T notSupported(Supplier<T> work) {
        return notSupported.execute(status -> work.get());
    }

    public void notSupported(Runnable work) {
        notSupported.executeWithoutResult(status -> work.run());
    }
}
