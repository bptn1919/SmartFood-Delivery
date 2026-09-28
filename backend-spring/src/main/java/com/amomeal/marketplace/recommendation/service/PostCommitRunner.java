package com.amomeal.marketplace.recommendation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring equivalent of Django's {@code transaction.on_commit(fn)} for the recommendation signal
 * hooks: runs {@code fn} after the surrounding transaction commits (immediately when there is
 * none, like Django's autocommit), and never inside that already-committed transaction.
 *
 * <p>Two execution modes, because Django's hooks run in autocommit:
 * <ul>
 *   <li>{@link #afterCommitNonTransactional} — {@code PROPAGATION_NOT_SUPPORTED}: each SQL
 *       statement autocommits, so a swallowed SQL error (Django's bare {@code except}) does not
 *       poison the statements after it — used for the raw-SQL vector refreshes.</li>
 *   <li>{@link #afterCommitInNewTransaction} — {@code PROPAGATION_REQUIRES_NEW}: for JPA writes
 *       (rebuild_user_feature, daily meal-log sync).</li>
 * </ul>
 * Spring's own docs require this: data access inside {@code afterCommit} otherwise still
 * "participates" in the finished transaction and is silently never committed (the §8b family of
 * traps).
 */
@Slf4j
@Component
public class PostCommitRunner {

    private final TransactionTemplate notSupported;
    private final TransactionTemplate requiresNew;

    public PostCommitRunner(PlatformTransactionManager transactionManager) {
        this.notSupported = new TransactionTemplate(transactionManager);
        this.notSupported.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void afterCommitNonTransactional(Runnable task) {
        schedule(() -> notSupported.executeWithoutResult(s -> task.run()));
    }

    public void afterCommitInNewTransaction(Runnable task) {
        schedule(() -> requiresNew.executeWithoutResult(s -> task.run()));
    }

    /**
     * Like {@link #afterCommitInNewTransaction} but any failure (including the new transaction's
     * rollback) is only logged — Django's {@code try: ... except Exception as exc: print(...)}
     * around an on_commit hook. The try must wrap the whole transaction, otherwise a swallowed
     * inner exception still surfaces as an UnexpectedRollbackException at commit.
     */
    public void afterCommitInNewTransactionLogged(Runnable task, String warningPrefix) {
        schedule(() -> {
            try {
                requiresNew.executeWithoutResult(s -> task.run());
            } catch (RuntimeException e) {
                log.warn("{}{}", warningPrefix, e.toString());
            }
        });
    }

    /** Run {@code task} with autocommit semantics right now (suspends any current transaction). */
    public void nonTransactional(Runnable task) {
        notSupported.executeWithoutResult(s -> task.run());
    }

    private void schedule(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
