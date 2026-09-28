package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.NotificationIdempotencyKey;
import com.amomeal.marketplace.order.repository.NotificationIdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The claim/release half of ../../backend/order/tasks.py::send_order_notification_task.
 *
 * <p>Django's {@code NotificationIdempotencyKey.objects.get_or_create(key=...)} runs
 * under autocommit: the claim is durable the instant it succeeds, and the
 * {@code claim.delete()} in the except-branch is durable before the retry is
 * raised. Per CLAUDE.md §8b each write therefore runs in its OWN transaction
 * ({@code REQUIRES_NEW}, programmatic so it holds no matter what the caller is
 * doing) — otherwise the delete would be rolled back by the very exception that
 * triggers the retry, and the retry would find its own stale claim and skip
 * itself forever.
 *
 * <p>The losing INSERT of a concurrent race runs in its own inner transaction
 * too, so the constraint violation rolls back only that attempt (a
 * rollback-only flag cannot leak into anything else).
 */
@Component
public class NotificationClaimService {

    private final NotificationIdempotencyKeyRepository repository;
    private final TransactionTemplate requiresNew;

    public NotificationClaimService(NotificationIdempotencyKeyRepository repository,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return true if THIS call created the key (it owns the side effect), false if
     *         the key already existed (a duplicate/redelivered task). The UNIQUE
     *         constraint decides genuinely concurrent races — a losing INSERT is
     *         caught, exactly like {@code get_or_create}'s internal IntegrityError
     *         handling.
     */
    public boolean claim(String key, String taskName) {
        Boolean exists = requiresNew.execute(s -> repository.existsByKey(key));
        if (Boolean.TRUE.equals(exists)) {
            return false;
        }
        try {
            requiresNew.executeWithoutResult(s -> repository.saveAndFlush(
                    NotificationIdempotencyKey.builder().key(key).taskName(taskName).build()));
            return true;
        } catch (DataIntegrityViolationException lostRace) {
            return false;
        }
    }

    /** Django: {@code claim.delete()} before {@code self.retry(exc=exc)}. */
    public void release(String key) {
        requiresNew.executeWithoutResult(s -> repository.deleteByKey(key));
    }
}
